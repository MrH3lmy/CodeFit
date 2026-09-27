package com.codefit.peer.identity.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * A PBKDF2-derived-key, AES/GCM-sealed byte string. The salt and iteration count travel in the
 * clear (they are not secret; they let a legitimate holder of the passphrase re-derive the same
 * key) while {@code ciphertext} is opaque and self-authenticating (GCM's tag detects any
 * modification, including a wrong passphrase, without leaking which one it was).
 */
public record EncryptedSecret(byte[] salt, int iterations, byte[] nonce, byte[] ciphertext) {

    public EncryptedSecret {
        salt = Objects.requireNonNull(salt, "salt").clone();
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive.");
        }
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedSecret that
                && iterations == that.iterations
                && Arrays.equals(salt, that.salt)
                && Arrays.equals(nonce, that.nonce)
                && Arrays.equals(ciphertext, that.ciphertext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(salt), iterations, Arrays.hashCode(nonce), Arrays.hashCode(ciphertext));
    }

    @Override
    public String toString() {
        // Deliberately omits salt/nonce/ciphertext bytes: this type wraps key material, and nothing
        // that wraps key material may render its bytes into a log line or exception message.
        return "EncryptedSecret[iterations=" + iterations + ", ciphertextLength=" + ciphertext.length + "]";
    }
}
