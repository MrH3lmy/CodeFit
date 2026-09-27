package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * Author-chosen random id of a logical object (a profile card, a daily summary, a consent record)
 * that keeps its id across revisions and its tombstone.
 */
public record ObjectId(byte[] bytes) {
    public static final int LENGTH = 16;

    public ObjectId {
        bytes = ProtocolBytes.copyExact(bytes, LENGTH, "ObjectId");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ObjectId that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ObjectId[" + ProtocolBytes.hex(bytes) + "]";
    }
}
