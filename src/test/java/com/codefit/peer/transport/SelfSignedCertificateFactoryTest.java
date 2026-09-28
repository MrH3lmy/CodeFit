package com.codefit.peer.transport;

import com.codefit.peer.identity.crypto.KeyPairs;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class SelfSignedCertificateFactoryTest {

    @Test
    void producesAJdkParseableSelfSignedEd25519Certificate() throws Exception {
        KeyPair keyPair = KeyPairs.generate();
        Instant notBefore = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant notAfter = notBefore.plus(400, ChronoUnit.DAYS);

        X509Certificate certificate = SelfSignedCertificateFactory.create(keyPair, notBefore, notAfter);

        // JDK's X509Certificate parser reports the generic EdDSA family name for OID 1.3.101.112
        // rather than "Ed25519" specifically; KeyPairs.rawPublicKey below is what actually proves it
        // decoded the correct 32-byte point.
        assertEquals("EdDSA", certificate.getPublicKey().getAlgorithm());
        assertArrayEquals(KeyPairs.rawPublicKey(keyPair.getPublic()), KeyPairs.rawPublicKey(certificate.getPublicKey()));
        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();
    }

    @Test
    void rejectsVerificationByAForeignKey() throws Exception {
        KeyPair keyPair = KeyPairs.generate();
        KeyPair foreign = KeyPairs.generate();
        X509Certificate certificate = SelfSignedCertificateFactory.create(keyPair, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600));

        assertThrows(Exception.class, () -> certificate.verify(foreign.getPublic()));
    }

    @Test
    void enforcesItsOwnValidityWindow() {
        KeyPair keyPair = KeyPairs.generate();
        Instant notBefore = Instant.now().plusSeconds(3600);
        Instant notAfter = notBefore.plusSeconds(3600);
        X509Certificate certificate = SelfSignedCertificateFactory.create(keyPair, notBefore, notAfter);

        assertThrows(CertificateNotYetValidException.class, certificate::checkValidity);
    }

    @Test
    void expiredCertificateFailsValidityCheck() {
        KeyPair keyPair = KeyPairs.generate();
        Instant notBefore = Instant.now().minusSeconds(7200);
        Instant notAfter = Instant.now().minusSeconds(3600);
        X509Certificate certificate = SelfSignedCertificateFactory.create(keyPair, notBefore, notAfter);

        assertThrows(CertificateExpiredException.class, certificate::checkValidity);
    }
}
