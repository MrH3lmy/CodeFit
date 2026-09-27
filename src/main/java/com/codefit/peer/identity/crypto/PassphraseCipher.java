package com.codefit.peer.identity.crypto;

import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.identity.VaultCorruptException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Seals and opens key material with a user passphrase, using only algorithms the JDK 21 {@code SunJCE}
 * provider supplies (see {@code docs/p2p/runtime-dependency-inventory.md} §3: "Key encryption at rest
 * (#181)"): {@code PBKDF2WithHmacSHA256} to derive a 256-bit key from the passphrase, then
 * {@code AES/GCM/NoPadding} to seal the plaintext. No custom cryptography.
 *
 * <p><strong>What this actually protects against, and what it does not.</strong> This is the entire
 * at-rest protection for the identity private key on every platform CodeFit runs on (#181 adds no OS
 * keychain, TPM, or DPAPI integration — each would be a new, platform-specific runtime dependency, and
 * the same passphrase-based scheme is what {@code docs/p2p} already commits to for encrypted backups).
 * A local attacker who copies the database file cannot use the private key without the passphrase. A
 * local attacker who also learns the passphrase (e.g. it is written down next to the disk, or captured
 * by malware already running as the user) can decrypt it; there is no secondary secret. Because GCM
 * authenticates the ciphertext, a wrong passphrase and a corrupted/tampered ciphertext both fail the
 * same tag check and are reported identically (see {@link VaultAuthenticationException}) — this is a
 * deliberate cryptographic property, not a gap in this implementation.
 */
public final class PassphraseCipher {
    static final int SALT_LENGTH_BYTES = 16;
    static final int NONCE_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** OWASP-recommended (2023) minimum work factor for PBKDF2-HMAC-SHA256; used for every new seal. */
    public static final int DEFAULT_ITERATIONS = 310_000;

    /**
     * Bounds accepted when opening secrets read from disk (a local vault row or an imported backup).
     * A maliciously crafted file could otherwise claim an absurd iteration count and hang the caller
     * before a single byte is authenticated — the same "bounded before read" discipline protocol v1
     * uses for lengths (docs/p2p/protocol-v1.md §1.2).
     */
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int MIN_ITERATIONS = 50_000;
    private static final int MAX_PLAINTEXT_LENGTH = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PassphraseCipher() {
    }

    public static EncryptedSecret seal(char[] passphrase, byte[] plaintext) {
        return seal(passphrase, plaintext, null);
    }

    /**
     * @param associatedData authenticated but not encrypted: bytes (e.g. the identity's public key,
     *                        or a backup's plaintext header) that must be presented unchanged at
     *                        {@link #open} for the tag to verify, so tampering with data that
     *                        deliberately travels outside the ciphertext is still detected. See
     *                        {@link IdentityBackupCodec} for why a backup's header needs this.
     */
    public static EncryptedSecret seal(char[] passphrase, byte[] plaintext, byte[] associatedData) {
        if (plaintext.length > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException("Refusing to seal an unexpectedly large secret.");
        }
        byte[] salt = randomBytes(SALT_LENGTH_BYTES);
        byte[] nonce = randomBytes(NONCE_LENGTH_BYTES);
        try {
            SecretKey key = deriveKey(passphrase, salt, DEFAULT_ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedSecret(salt, DEFAULT_ITERATIONS, nonce, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to seal key material with the JDK's AES/GCM implementation.", e);
        }
    }

    public static byte[] open(char[] passphrase, EncryptedSecret secret) {
        return open(passphrase, secret, null);
    }

    /**
     * @throws VaultCorruptException        the secret's own parameters (iteration count, salt/nonce
     *                                       length) are out of bounds, so no decryption was attempted
     * @throws VaultAuthenticationException the passphrase is wrong, the ciphertext was modified, or
     *                                       {@code associatedData} does not match what {@link #seal}
     *                                       was given; AES/GCM cannot distinguish these (see class docs)
     */
    public static byte[] open(char[] passphrase, EncryptedSecret secret, byte[] associatedData) {
        validateBounds(secret);
        try {
            SecretKey key = deriveKey(passphrase, secret.salt(), secret.iterations());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, secret.nonce()));
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            return cipher.doFinal(secret.ciphertext());
        } catch (AEADBadTagException e) {
            throw new VaultAuthenticationException("Wrong passphrase, or the encrypted key material is corrupted.");
        } catch (GeneralSecurityException e) {
            throw new VaultAuthenticationException("Unable to open the encrypted key material.");
        }
    }

    private static void validateBounds(EncryptedSecret secret) {
        if (secret.salt().length != SALT_LENGTH_BYTES) {
            throw new VaultCorruptException("Encrypted key material has an invalid salt length.");
        }
        if (secret.nonce().length != NONCE_LENGTH_BYTES) {
            throw new VaultCorruptException("Encrypted key material has an invalid nonce length.");
        }
        if (secret.iterations() < MIN_ITERATIONS || secret.iterations() > MAX_ITERATIONS) {
            throw new VaultCorruptException("Encrypted key material declares an out-of-range iteration count.");
        }
        if (secret.ciphertext().length == 0 || secret.ciphertext().length > MAX_PLAINTEXT_LENGTH + 64) {
            throw new VaultCorruptException("Encrypted key material has an invalid ciphertext length.");
        }
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
