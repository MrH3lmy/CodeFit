package com.codefit.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static com.codefit.knowledge.SyntheticReviewEvidenceValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewEvidenceValidatorTest {
    private static final String AUTHOR = "synthetic-author";
    private static final String OWNER = "synthetic-owner";
    private static final String TECHNICAL = "synthetic-technical";
    private static final String PEDAGOGICAL = "synthetic-pedagogical";
    private Candidate candidate;
    private KeyPair ownerKey, technicalKey, pedagogicalKey;
    private TrustContext trust;
    private Proof technical, pedagogical, adjudication, distribution, release;

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        candidate = candidate("v1", "fixture content", "evaluator-v1", "fixture evaluator");
        ownerKey = keyPair();
        technicalKey = keyPair();
        pedagogicalKey = keyPair();
        trust = trust(ownerKey, technicalKey, pedagogicalKey, false, false);
        technical = proof(TECHNICAL, Action.TECHNICAL_REVIEW, Decision.ACCEPT, "", "", false, "1");
        pedagogical = proof(PEDAGOGICAL, Action.PEDAGOGICAL_REVIEW, Decision.ACCEPT, "", "", false, "2");
        adjudication = proof(OWNER, Action.OWNER_ADJUDICATION, Decision.ACCEPT,
                technical.digest(), pedagogical.digest(), false, "3");
        distribution = proof(OWNER, Action.DISTRIBUTION_AUTHORIZATION,
                Decision.AUTHORIZE_PUBLIC_DISTRIBUTION, "synthetic-license-v1", "PUBLIC", false, "4");
        release = proof(OWNER, Action.RELEASE_AUTHORIZATION, Decision.AUTHORIZE_RELEASE,
                adjudication.digest(), distribution.digest(), false, "5");
    }

    @Test
    void draftAndPartialReviewRemainDeniedByDefault() {
        assertStatus(Status.AUTHOR_DRAFT, List.of(), null, null, null);
        assertStatus(Status.PEDAGOGY_REVIEW_PENDING, List.of(technical), null, null, null);
        assertStatus(Status.TECHNICAL_REVIEW_PENDING, List.of(pedagogical), null, null, null);
    }

    @Test
    void completeEvidenceStillDoesNotEnableProductionGradingOrActivation() {
        Verdict verdict = evaluate(candidate, trust, List.of(technical, pedagogical),
                adjudication, distribution, release);
        assertEquals(Status.RELEASE_AUTHORIZED, verdict.status());
        assertFalse(verdict.graderAvailable());
        assertFalse(verdict.productionActivation());
    }

    @Test
    void reviewSignatureMustBePresentValidCurrentAndUnrevoked() throws GeneralSecurityException {
        Proof unsigned = new Proof(technical.actorId(), technical.action(), technical.decision(),
                technical.manifestSha256(), technical.revision(), technical.revisionSha256(),
                technical.evaluatorVersion(), technical.evaluatorSha256(), "", "", false,
                technical.nonce(), new byte[0]);
        assertDenied(List.of(unsigned, pedagogical), adjudication, distribution, release, trust, candidate);

        List<Credential> revokedCredentials = List.of(
                new Credential(OWNER, Role.OWNER, ownerKey.getPublic(), false),
                new Credential(TECHNICAL, Role.TECHNICAL, technicalKey.getPublic(), true),
                new Credential(PEDAGOGICAL, Role.PEDAGOGICAL, pedagogicalKey.getPublic(), false));
        TrustContext revoked = new TrustContext(revokedCredentials, OWNER, rosterPin(revokedCredentials));
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, revoked, candidate);

        Proof changed = new Proof(technical.actorId(), technical.action(), technical.decision(),
                technical.manifestSha256(), technical.revision(), technical.revisionSha256(),
                technical.evaluatorVersion(), technical.evaluatorSha256(), "", "", false,
                technical.nonce(), new byte[]{1, 2, 3});
        assertDenied(List.of(changed, pedagogical), adjudication, distribution, release, trust, candidate);
    }

    @Test
    void authorReviewerConflictsAndReviewerReuseAreRejected() {
        List<Credential> authorCredentials = List.of(
                new Credential(OWNER, Role.OWNER, ownerKey.getPublic(), false),
                new Credential(AUTHOR, Role.TECHNICAL, technicalKey.getPublic(), false),
                new Credential(PEDAGOGICAL, Role.PEDAGOGICAL, pedagogicalKey.getPublic(), false));
        TrustContext authorTrust = new TrustContext(authorCredentials, OWNER, rosterPin(authorCredentials));
        Proof authorReview = sign(candidate, AUTHOR, Action.TECHNICAL_REVIEW, Decision.ACCEPT,
                "", "", false, String.format("%032x", 6), technicalKey.getPrivate());
        assertDenied(List.of(authorReview, pedagogical), adjudication, distribution, release, authorTrust, candidate);

        Proof conflict = proof(TECHNICAL, Action.TECHNICAL_REVIEW, Decision.ACCEPT, "", "", true, "7");
        assertDenied(List.of(conflict, pedagogical), adjudication, distribution, release, trust, candidate);

        Proof sameReviewerPedagogy = proof(TECHNICAL, Action.PEDAGOGICAL_REVIEW,
                Decision.ACCEPT, "", "", false, "8");
        assertDenied(List.of(technical, sameReviewerPedagogy), adjudication, distribution, release, trust, candidate);

        Proof rejected = proof(TECHNICAL, Action.TECHNICAL_REVIEW, Decision.NEEDS_REVISION,
                "", "", false, "10");
        assertDenied(List.of(rejected, pedagogical), adjudication, distribution, release, trust, candidate);
    }

    @Test
    void staleContentRevisionOrEvaluatorCannotReusePriorReview() {
        Candidate changedContent = candidate("v2", "changed content", "evaluator-v1", "fixture evaluator");
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, trust, changedContent);

        Candidate changedEvaluator = candidate("v1", "fixture content", "evaluator-v2", "changed evaluator");
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, trust, changedEvaluator);
    }

    @Test
    void ownerAdjudicationLicensingAndReleaseAreSeparateGates() {
        assertStatus(Status.OWNER_ADJUDICATION_PENDING, List.of(technical, pedagogical), null, null, null);
        assertStatus(Status.REVIEWED_UNRELEASED, List.of(technical, pedagogical), adjudication, null, null);
        assertStatus(Status.REVIEWED_UNRELEASED, List.of(technical, pedagogical), adjudication, distribution, null);

        Proof wrongReleaseTarget = proof(OWNER, Action.RELEASE_AUTHORIZATION, Decision.AUTHORIZE_RELEASE,
                adjudication.digest(), "0".repeat(64), false, "9");
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, wrongReleaseTarget, trust, candidate);

        Proof unsignedOwnerDecision = new Proof(adjudication.actorId(), adjudication.action(),
                adjudication.decision(), adjudication.manifestSha256(), adjudication.revision(),
                adjudication.revisionSha256(), adjudication.evaluatorVersion(), adjudication.evaluatorSha256(),
                adjudication.relatedFirst(), adjudication.relatedSecond(), false, adjudication.nonce(), new byte[0]);
        assertDenied(List.of(technical, pedagogical), unsignedOwnerDecision, distribution, release, trust, candidate);
    }

    @Test
    void forgedApprovalAndReleaseMetadataCannotOverrideEvidence() {
        Candidate forged = new Candidate(candidate.packetId(), candidate.revision(), candidate.manifest(),
                candidate.content(), candidate.evaluatorVersion(), candidate.evaluator(), AUTHOR,
                "APPROVED", 2, true, true);
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, trust, forged);
    }

    @Test
    void manifestMustPinItsExactContentAndEvaluator() {
        Candidate mismatchedManifest = new Candidate(candidate.packetId(), candidate.revision(),
                bytes("different manifest"), candidate.content(), candidate.evaluatorVersion(),
                candidate.evaluator(), AUTHOR, "AUTHOR_DRAFT", 0, false, false);
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, trust, mismatchedManifest);

        Proof privateDistribution = proof(OWNER, Action.DISTRIBUTION_AUTHORIZATION,
                Decision.AUTHORIZE_PUBLIC_DISTRIBUTION, "synthetic-license-v1", "PRIVATE", false, "11");
        assertDenied(List.of(technical, pedagogical), adjudication, privateDistribution, release, trust, candidate);
    }

    @Test
    void rosterPinAndReplayAreFailClosed() throws GeneralSecurityException {
        TrustContext tampered = new TrustContext(trust.credentials(), OWNER, "0".repeat(64));
        assertDenied(List.of(technical, pedagogical), adjudication, distribution, release, tampered, candidate);

        Proof replayedNonce = proof(OWNER, Action.DISTRIBUTION_AUTHORIZATION,
                Decision.AUTHORIZE_PUBLIC_DISTRIBUTION, "synthetic-license-v1", "PUBLIC", false, "3");
        assertDenied(List.of(technical, pedagogical), adjudication, replayedNonce, release, trust, candidate);
    }

    private void assertStatus(Status expected, List<Proof> reviews, Proof owner, Proof rights, Proof releaseProof) {
        Verdict verdict = evaluate(candidate, trust, reviews, owner, rights, releaseProof);
        assertEquals(expected, verdict.status());
        assertFalse(verdict.graderAvailable());
        assertFalse(verdict.productionActivation());
    }

    private static void assertDenied(List<Proof> reviews, Proof owner, Proof rights, Proof releaseProof,
                                     TrustContext trust, Candidate candidate) {
        Verdict verdict = evaluate(candidate, trust, reviews, owner, rights, releaseProof);
        assertEquals(Status.DENIED, verdict.status());
        assertFalse(verdict.graderAvailable());
        assertFalse(verdict.productionActivation());
    }

    private Candidate candidate(String revision, String content, String evaluatorVersion, String evaluator) {
        byte[] contentBytes = bytes(content), evaluatorBytes = bytes(evaluator);
        byte[] manifest = SyntheticReviewEvidenceValidator.encode("codefit-synthetic-manifest/1",
                "synthetic-original-fixture", revision, sha256(contentBytes), evaluatorVersion,
                sha256(evaluatorBytes));
        return new Candidate("synthetic-original-fixture", revision, manifest, contentBytes,
                evaluatorVersion, evaluatorBytes, AUTHOR, "AUTHOR_DRAFT", 0, false, false);
    }

    private Proof proof(String actor, Action action, Decision decision, String first, String second,
                        boolean conflict, String nonceSuffix) {
        KeyPair key = actor.equals(OWNER) ? ownerKey : actor.equals(TECHNICAL) ? technicalKey
                : actor.equals(PEDAGOGICAL) ? pedagogicalKey : null;
        if (key == null) throw new IllegalArgumentException("fixture identity has no synthetic credential");
        String nonce = String.format("%032x", Long.parseLong(nonceSuffix));
        return sign(candidate, actor, action, decision, first, second, conflict, nonce, key.getPrivate());
    }

    private static TrustContext trust(KeyPair owner, KeyPair technical, KeyPair pedagogical,
                                      boolean revokeTechnical, boolean revokePedagogical) {
        List<Credential> credentials = List.of(
                new Credential(OWNER, Role.OWNER, owner.getPublic(), false),
                new Credential(TECHNICAL, Role.TECHNICAL, technical.getPublic(), revokeTechnical),
                new Credential(PEDAGOGICAL, Role.PEDAGOGICAL, pedagogical.getPublic(), revokePedagogical));
        return new TrustContext(credentials, OWNER, rosterPin(credentials));
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
