package com.codefit.peer.identity;

/**
 * Thrown when decrypting locally-stored key material fails: a wrong passphrase or tampered/corrupted
 * ciphertext. AES/GCM cannot distinguish the two (a wrong key and a modified ciphertext both fail the
 * same authentication tag check), so this exception deliberately carries no detail beyond that fact.
 * Callers MUST NOT fall back to creating a new identity when this is thrown (see #181): a load failure
 * is reported, never silently repaired by regenerating identity.
 */
public class VaultAuthenticationException extends RuntimeException {
    public VaultAuthenticationException(String message) {
        super(message);
    }
}
