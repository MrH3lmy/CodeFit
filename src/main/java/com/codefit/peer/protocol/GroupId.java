package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * Author-chosen random id of a private challenge/group (#186). Never all-zero.
 */
public record GroupId(byte[] bytes) {
    public static final int LENGTH = 16;

    public GroupId {
        bytes = ProtocolBytes.copyExact(bytes, LENGTH, "GroupId");
        if (ProtocolBytes.allZero(bytes)) {
            throw new IllegalArgumentException("GroupId must not be all-zero.");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GroupId that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "GroupId[" + ProtocolBytes.hex(bytes) + "]";
    }
}
