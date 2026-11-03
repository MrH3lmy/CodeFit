package com.codefit.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Test-only policy model. Its synthetic proof format is not a production attestation format. */
final class SyntheticReviewEvidenceValidator {
    enum Role { TECHNICAL, PEDAGOGICAL, OWNER }
    enum Action { TECHNICAL_REVIEW, PEDAGOGICAL_REVIEW, OWNER_ADJUDICATION, DISTRIBUTION_AUTHORIZATION, RELEASE_AUTHORIZATION }
    enum Decision { ACCEPT, REJECT, NEEDS_REVISION, AUTHORIZE_PUBLIC_DISTRIBUTION, AUTHORIZE_RELEASE }
    enum Status {
        AUTHOR_DRAFT, TECHNICAL_REVIEW_PENDING, PEDAGOGY_REVIEW_PENDING,
        OWNER_ADJUDICATION_PENDING, REVIEWED_UNRELEASED, RELEASE_AUTHORIZED, DENIED
    }

    record Candidate(String packetId, String revision, byte[] manifest, byte[] content, String evaluatorVersion,
                     byte[] evaluator, String authorId, String claimedStatus, int claimedApprovals,
                     boolean claimedPublication, boolean claimedGrader) {
        Candidate {
            manifest = manifest.clone();
            content = content.clone();
            evaluator = evaluator.clone();
        }
        @Override public byte[] manifest() { return manifest.clone(); }
        @Override public byte[] content() { return content.clone(); }
        @Override public byte[] evaluator() { return evaluator.clone(); }
        String manifestSha256() { return sha256(manifest); }
        String revisionSha256() { return sha256(content); }
        String evaluatorSha256() { return sha256(evaluator); }
    }

    record Credential(String id, Role role, PublicKey publicKey, boolean revoked) { }
    record TrustContext(List<Credential> credentials, String ownerId, String outOfBandRosterPin) {
        TrustContext { credentials = List.copyOf(credentials); }
    }
    record Proof(String actorId, Action action, Decision decision, String manifestSha256, String revision,
                 String revisionSha256, String evaluatorVersion, String evaluatorSha256,
                 String relatedFirst, String relatedSecond, boolean conflictPresent, String nonce, byte[] signature) {
        Proof { signature = signature.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
        byte[] payload() {
            return encode("codefit-synthetic-review-evidence/1", actorId, action.name(), decision.name(),
                    manifestSha256, revision, revisionSha256, evaluatorVersion, evaluatorSha256,
                    relatedFirst, relatedSecond, Boolean.toString(conflictPresent), nonce);
        }
        String digest() { return sha256(concat(payload(), signature)); }
    }
    record Verdict(Status status, boolean graderAvailable, boolean productionActivation) { }

    private SyntheticReviewEvidenceValidator() { }

    static String rosterPin(List<Credential> credentials) {
        List<Credential> sorted = credentials.stream().sorted(Comparator.comparing(Credential::id)).toList();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Credential credential : sorted) {
            bytes.writeBytes(encode(credential.id(), credential.role().name(),
                    Base64.getEncoder().encodeToString(credential.publicKey().getEncoded()),
                    Boolean.toString(credential.revoked())));
        }
        return sha256(bytes.toByteArray());
    }

    static Proof sign(Candidate candidate, String actorId, Action action, Decision decision,
                      String relatedFirst, String relatedSecond, boolean conflictPresent, String nonce,
                      PrivateKey key) {
        Proof unsigned = new Proof(actorId, action, decision, candidate.manifestSha256(), candidate.revision(),
                candidate.revisionSha256(), candidate.evaluatorVersion(), candidate.evaluatorSha256(),
                relatedFirst, relatedSecond, conflictPresent, nonce, new byte[0]);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(unsigned.payload());
            return new Proof(actorId, action, decision, candidate.manifestSha256(), candidate.revision(),
                    candidate.revisionSha256(), candidate.evaluatorVersion(), candidate.evaluatorSha256(),
                    relatedFirst, relatedSecond, conflictPresent, nonce, signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Verdict evaluate(Candidate candidate, TrustContext trust, List<Proof> reviews,
                            Proof adjudication, Proof distributionAuthorization, Proof releaseAuthorization) {
        if (!validCandidate(candidate) || !validTrust(trust) || reviews == null || reviews.size() > 2) return denied();
        EnumMap<Role, Proof> byRole = new EnumMap<>(Role.class);
        Set<String> nonces = new HashSet<>();
        for (Proof proof : reviews) {
            if (proof == null || (proof.action() != Action.TECHNICAL_REVIEW
                    && proof.action() != Action.PEDAGOGICAL_REVIEW)) return denied();
            Role role = proof.action() == Action.TECHNICAL_REVIEW ? Role.TECHNICAL : Role.PEDAGOGICAL;
            if (byRole.putIfAbsent(role, proof) != null || !nonces.add(proof.nonce())
                    || !validProof(candidate, trust, proof, role)
                    || proof.decision() != Decision.ACCEPT || proof.conflictPresent()
                    || proof.actorId().equals(candidate.authorId())
                    || proof.actorId().equals(trust.ownerId())) return denied();
        }
        if (byRole.isEmpty()) return new Verdict(Status.AUTHOR_DRAFT, false, false);
        if (!byRole.containsKey(Role.TECHNICAL))
            return new Verdict(Status.TECHNICAL_REVIEW_PENDING, false, false);
        if (!byRole.containsKey(Role.PEDAGOGICAL))
            return new Verdict(Status.PEDAGOGY_REVIEW_PENDING, false, false);
        Proof technical = byRole.get(Role.TECHNICAL), pedagogical = byRole.get(Role.PEDAGOGICAL);
        if (technical.actorId().equals(pedagogical.actorId())) return denied();
        if (adjudication == null)
            return new Verdict(Status.OWNER_ADJUDICATION_PENDING, false, false);
        if (!nonces.add(adjudication.nonce()) || !validOwnerProof(candidate, trust, adjudication,
                Action.OWNER_ADJUDICATION, Decision.ACCEPT)
                || !Objects.equals(adjudication.relatedFirst(), technical.digest())
                || !Objects.equals(adjudication.relatedSecond(), pedagogical.digest())
                || adjudication.actorId().equals(candidate.authorId())
                || adjudication.actorId().equals(technical.actorId())
                || adjudication.actorId().equals(pedagogical.actorId())) return denied();
        if (distributionAuthorization == null)
            return new Verdict(Status.REVIEWED_UNRELEASED, false, false);
        if (!nonces.add(distributionAuthorization.nonce())
                || !validOwnerProof(candidate, trust, distributionAuthorization,
                        Action.DISTRIBUTION_AUTHORIZATION, Decision.AUTHORIZE_PUBLIC_DISTRIBUTION)
                || !validLicenseScope(distributionAuthorization))
            return denied();
        if (releaseAuthorization == null)
            return new Verdict(Status.REVIEWED_UNRELEASED, false, false);
        if (!nonces.add(releaseAuthorization.nonce())
                || !validOwnerProof(candidate, trust, releaseAuthorization,
                        Action.RELEASE_AUTHORIZATION, Decision.AUTHORIZE_RELEASE)
                || !Objects.equals(releaseAuthorization.relatedFirst(), adjudication.digest())
                || !Objects.equals(releaseAuthorization.relatedSecond(), distributionAuthorization.digest()))
            return denied();
        return new Verdict(Status.RELEASE_AUTHORIZED, false, false);
    }

    private static boolean validCandidate(Candidate candidate) {
        return candidate != null && candidate.packetId() != null && candidate.packetId().matches("[a-z0-9.-]{1,128}")
                && candidate.revision() != null && candidate.revision().matches("v[0-9]+(?:\\.[0-9]+)*")
                && validHash(candidate.manifestSha256()) && validHash(candidate.revisionSha256())
                && validHash(candidate.evaluatorSha256()) && candidate.evaluatorVersion() != null
                && candidate.evaluatorVersion().matches("[A-Za-z0-9._-]{1,64}")
                && Arrays.equals(candidate.manifest(), expectedManifest(candidate))
                && candidate.authorId() != null && !candidate.authorId().isBlank()
                && "AUTHOR_DRAFT".equals(candidate.claimedStatus()) && candidate.claimedApprovals() == 0
                && !candidate.claimedPublication() && !candidate.claimedGrader();
    }

    private static boolean validTrust(TrustContext trust) {
        if (trust == null || trust.ownerId() == null || !validHash(trust.outOfBandRosterPin())) return false;
        Set<String> ids = new HashSet<>();
        for (Credential credential : trust.credentials()) {
            if (credential == null || credential.id() == null || credential.id().isBlank()
                    || credential.role() == null || credential.publicKey() == null || !ids.add(credential.id()))
                return false;
        }
        long owners = trust.credentials().stream().filter(c -> c.id().equals(trust.ownerId())
                && c.role() == Role.OWNER && !c.revoked()).count();
        return owners == 1 && rosterPin(trust.credentials()).equals(trust.outOfBandRosterPin());
    }

    private static boolean validProof(Candidate candidate, TrustContext trust, Proof proof, Role expectedRole) {
        Credential credential = credential(trust, proof.actorId());
        return credential != null && credential.role() == expectedRole && !credential.revoked()
                && matches(candidate, proof) && verify(proof, credential.publicKey());
    }

    private static boolean validOwnerProof(Candidate candidate, TrustContext trust, Proof proof,
                                           Action action, Decision decision) {
        Credential credential = credential(trust, proof.actorId());
        return proof.action() == action && proof.decision() == decision && credential != null
                && credential.role() == Role.OWNER && !credential.revoked()
                && proof.actorId().equals(trust.ownerId()) && !proof.conflictPresent()
                && matches(candidate, proof) && verify(proof, credential.publicKey());
    }

    private static boolean validLicenseScope(Proof proof) {
        return proof.relatedFirst() != null && proof.relatedFirst().matches("[a-z0-9._-]{1,64}")
                && "PUBLIC".equals(proof.relatedSecond());
    }

    private static byte[] expectedManifest(Candidate candidate) {
        return encode("codefit-synthetic-manifest/1", candidate.packetId(), candidate.revision(),
                candidate.revisionSha256(), candidate.evaluatorVersion(), candidate.evaluatorSha256());
    }

    private static Credential credential(TrustContext trust, String id) {
        return trust.credentials().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean matches(Candidate candidate, Proof proof) {
        return Objects.equals(proof.manifestSha256(), candidate.manifestSha256())
                && Objects.equals(proof.revision(), candidate.revision())
                && Objects.equals(proof.revisionSha256(), candidate.revisionSha256())
                && Objects.equals(proof.evaluatorVersion(), candidate.evaluatorVersion())
                && Objects.equals(proof.evaluatorSha256(), candidate.evaluatorSha256())
                && proof.nonce() != null && proof.nonce().matches("[a-f0-9]{32}")
                && validHash(proof.manifestSha256()) && validHash(proof.revisionSha256())
                && validHash(proof.evaluatorSha256());
    }

    private static boolean verify(Proof proof, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(proof.payload());
            return verifier.verify(proof.signature());
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static Verdict denied() { return new Verdict(Status.DENIED, false, false); }
    private static boolean validHash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
    static byte[] encode(String... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value : values) {
                    byte[] encoded = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
