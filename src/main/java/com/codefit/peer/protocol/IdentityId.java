package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * SHA-256 of an {@link IdentityKey}; the identity reference used in audiences and group membership.
 * Comparable by unsigned bytes so audiences have one canonical order.
 */
public record IdentityId(byte[] bytes) implements Comparable<IdentityId> {
    public static final int LENGTH = 32;

    public IdentityId {
        bytes = ProtocolBytes.copyExact(bytes, LENGTH, "IdentityId");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public int compareTo(IdentityId other) {
        return ProtocolBytes.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityId that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "IdentityId[" + ProtocolBytes.hex(bytes) + "]";
    }
}
