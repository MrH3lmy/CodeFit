package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * A peer's long-term Ed25519 public identity key (RFC 8032 32-byte encoding). This is the only thing a
 * CodeFit social identity <em>is</em>: display names are self-asserted labels, not registered names.
 * Private key material never appears in any protocol type.
 */
public record IdentityKey(byte[] bytes) {
    public static final int LENGTH = 32;

    public IdentityKey {
        bytes = ProtocolBytes.copyExact(bytes, LENGTH, "Identity key");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The stable identifier used in audiences: SHA-256 of the raw public key. */
    public IdentityId id() {
        return new IdentityId(ProtocolBytes.sha256(bytes));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "IdentityKey[" + ProtocolBytes.hex(bytes) + "]";
    }
}
