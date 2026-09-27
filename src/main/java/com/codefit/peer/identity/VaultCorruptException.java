package com.codefit.peer.identity;

/**
 * Thrown when locally-stored or imported key material is structurally unreadable (truncated, an
 * out-of-range length, or a field that cannot be parsed) before any decryption is even attempted.
 * Distinct from {@link VaultAuthenticationException}, which means the bytes parsed but the
 * passphrase/authentication tag did not check out.
 */
public class VaultCorruptException extends RuntimeException {
    public VaultCorruptException(String message) {
        super(message);
    }
}
