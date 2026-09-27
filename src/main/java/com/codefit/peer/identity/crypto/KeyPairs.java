package com.codefit.peer.identity.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Ed25519 key generation, raw-key (RFC 8032, 32 bytes) encoding, and signing through the JDK's
 * built-in {@code SunEC} provider only (Java SE 21 Standard Algorithm Names; the same provider
 * {@code com.codefit.peer.protocol.Ed25519} uses for envelope signatures). This class is
 * production identity generation and private-key handling, which is deliberately out of scope for
 * #180's protocol package (see {@code docs/p2p/adr-0001-decentralized-peer-architecture.md} §1) and
 * belongs here in #181.
 */
public final class KeyPairs {
    public static final int PUBLIC_KEY_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    /** DER SEQUENCE {@code {SEQUENCE {OID 1.3.101.112}, BIT STRING (33)}} preceding a raw Ed25519 key. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /**
     * A fixed, non-secret message signed and verified purely to prove that a decrypted private key and
     * its declared public key are actually a pair (RFC 8032 gives no other cheap way to derive one from
     * the other through the JDK's public API). Never transmitted or treated as an envelope signature.
     */
    private static final byte[] KEY_PAIR_CONSISTENCY_CHALLENGE =
            "CodeFit-Ed25519-KeyPair-Consistency-Check-v1".getBytes(StandardCharsets.US_ASCII);

    private KeyPairs() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK does not provide Ed25519 (SunEC/jdk.crypto.ec missing).", e);
        }
    }

    /** The raw 32-byte Ed25519 public key point, stripped of its X.509 SubjectPublicKeyInfo wrapper. */
    public static byte[] rawPublicKey(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        if (encoded.length != SPKI_PREFIX.length + PUBLIC_KEY_LENGTH) {
            throw new IllegalStateException("Unexpected Ed25519 public key encoding length: " + encoded.length);
        }
        byte[] raw = new byte[PUBLIC_KEY_LENGTH];
        System.arraycopy(encoded, SPKI_PREFIX.length, raw, 0, PUBLIC_KEY_LENGTH);
        return raw;
    }

    public static PublicKey publicKeyFromRaw(byte[] raw) {
        if (raw.length != PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException("Ed25519 public key must be exactly " + PUBLIC_KEY_LENGTH + " bytes.");
        }
        byte[] spki = new byte[SPKI_PREFIX.length + PUBLIC_KEY_LENGTH];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, PUBLIC_KEY_LENGTH);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 public key.", e);
        }
    }

    /** PKCS#8-encoded private key bytes, the plaintext this package always keeps sealed at rest. */
    public static byte[] pkcs8PrivateKey(PrivateKey privateKey) {
        return privateKey.getEncoded();
    }

    public static PrivateKey privateKeyFromPkcs8(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid PKCS#8-encoded Ed25519 private key.", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not sign with the supplied Ed25519 key.", e);
        }
    }

    /**
     * Whether {@code privateKey} and {@code publicKey} are actually a matching Ed25519 pair, proven by
     * signing a fixed challenge with one and verifying it with the other. A decrypted private key is
     * never trusted to belong to a stored/declared public key without this check: the two travel
     * separately (the public key in the clear, the private key inside AEAD ciphertext keyed by a
     * passphrase), and only AEAD authentication, not key-pair consistency, guards the private key.
     */
    public static boolean matches(PrivateKey privateKey, PublicKey publicKey) {
        return verify(publicKey, KEY_PAIR_CONSISTENCY_CHALLENGE, sign(privateKey, KEY_PAIR_CONSISTENCY_CHALLENGE));
    }

    public static boolean verify(PublicKey publicKey, byte[] message, byte[] signature) {
        if (signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
