package com.codefit.peer.transport;

import com.codefit.peer.identity.crypto.KeyPairs;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.io.ByteArrayInputStream;

/**
 * Builds the one fixed self-signed X.509v3 certificate template #182 needs: an Ed25519 public key
 * (RFC 8410, OID 1.3.101.112) as both issuer and subject, signed by the matching private key, with no
 * extensions. This is deliberately not a general-purpose certificate builder — JDK 21 has no public one
 * (ADR-0001 §5), and the dependency inventory's recorded default is this small in-repo DER encoder
 * instead of adding Bouncy Castle. The certificate's only job is to carry the transport public key
 * inside a TLS handshake; every actual trust decision is made afterward by
 * {@link StructuralTransportTrustManager}/{@link PinnedTransportTrustManager} and the post-handshake
 * {@code IDENTITY_BINDING} exchange ({@link PeerSession}), never by chain validation against this
 * certificate's issuer/subject fields, which exist only because X.509 requires them.
 */
final class SelfSignedCertificateFactory {
    private static final String COMMON_NAME = "CodeFit-Transport";

    private SelfSignedCertificateFactory() {
    }

    static X509Certificate create(KeyPair transportKeyPair, Instant notBefore, Instant notAfter) {
        byte[] rawPublicKey = KeyPairs.rawPublicKey(transportKeyPair.getPublic());
        byte[] serialNumber = new byte[8];
        new SecureRandom().nextBytes(serialNumber);

        byte[] version = Der.contextExplicit(0, Der.positiveInteger(new byte[]{2})); // v3
        byte[] serial = Der.positiveInteger(serialNumber);
        byte[] signatureAlgorithm = Der.ed25519AlgorithmIdentifier();
        byte[] issuer = Der.commonNameOnly(COMMON_NAME);
        byte[] validity = Der.sequence(Der.time(notBefore), Der.time(notAfter));
        byte[] subject = issuer; // self-signed: subject == issuer
        byte[] subjectPublicKeyInfo = Der.sequence(Der.ed25519AlgorithmIdentifier(), Der.bitStringOfBytes(rawPublicKey));

        byte[] tbsCertificate = Der.sequence(version, serial, signatureAlgorithm, issuer, validity, subject, subjectPublicKeyInfo);
        byte[] signature = KeyPairs.sign(transportKeyPair.getPrivate(), tbsCertificate);
        byte[] certificate = Der.sequence(tbsCertificate, signatureAlgorithm, Der.bitStringOfBytes(signature));

        return parse(certificate);
    }

    /** Re-signs the same template with a different key pair; used only by tests to build a bad/foreign certificate. */
    static X509Certificate createSignedBy(KeyPair subjectKeyPair, PrivateKey signerPrivateKey, Instant notBefore, Instant notAfter) {
        byte[] rawPublicKey = KeyPairs.rawPublicKey(subjectKeyPair.getPublic());
        byte[] serialNumber = new byte[8];
        new SecureRandom().nextBytes(serialNumber);

        byte[] version = Der.contextExplicit(0, Der.positiveInteger(new byte[]{2}));
        byte[] serial = Der.positiveInteger(serialNumber);
        byte[] signatureAlgorithm = Der.ed25519AlgorithmIdentifier();
        byte[] issuer = Der.commonNameOnly(COMMON_NAME);
        byte[] validity = Der.sequence(Der.time(notBefore), Der.time(notAfter));
        byte[] subjectPublicKeyInfo = Der.sequence(Der.ed25519AlgorithmIdentifier(), Der.bitStringOfBytes(rawPublicKey));

        byte[] tbsCertificate = Der.sequence(version, serial, signatureAlgorithm, issuer, validity, issuer, subjectPublicKeyInfo);
        byte[] signature = KeyPairs.sign(signerPrivateKey, tbsCertificate);
        byte[] certificate = Der.sequence(tbsCertificate, signatureAlgorithm, Der.bitStringOfBytes(signature));
        return parse(certificate);
    }

    private static X509Certificate parse(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IllegalStateException("Generated an invalid self-signed certificate.", e);
        }
    }
}
