package com.codefit.peer.protocol;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Ed25519 (RFC 8032) through the JDK's built-in {@code SunEC} provider (Java 15+, standard algorithm
 * name {@code "Ed25519"}); no third-party cryptography. Raw 32-byte public keys are wrapped in the
 * fixed RFC 8410 SubjectPublicKeyInfo prefix for {@code X509EncodedKeySpec}.
 *
 * <p>This class signs with a caller-supplied key and verifies; it never generates or stores keys.
 * Local identity creation and encrypted key storage belong to #181.
 */
final class Ed25519 {
    static final int SIGNATURE_LENGTH = 64;
    /** DER SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING (33) } header preceding the raw key. */
    private static final byte[] SPKI_PREFIX = java.util.HexFormat.of().parseHex("302a300506032b6570032100");

    private Ed25519() {
    }

    static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not sign with the supplied Ed25519 key.", e);
        }
    }

    static boolean verify(IdentityKey publicKey, byte[] message, byte[] signature) {
        if (signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(toJdkPublicKey(publicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Undecodable point / invalid key encoding: authentication failure, not a crash.
            return false;
        }
    }

    static PublicKey toJdkPublicKey(IdentityKey key) throws GeneralSecurityException {
        byte[] spki = new byte[SPKI_PREFIX.length + IdentityKey.LENGTH];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(key.bytes(), 0, spki, SPKI_PREFIX.length, IdentityKey.LENGTH);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }
}
