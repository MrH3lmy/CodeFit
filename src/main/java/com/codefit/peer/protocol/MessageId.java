package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * SHA-256 of one complete signed envelope encoding. Computed, never transmitted, so it cannot
 * disagree with the bytes it names; the duplicate-detection key.
 */
public record MessageId(byte[] bytes) {
    public static final int LENGTH = 32;

    public MessageId {
        bytes = ProtocolBytes.copyExact(bytes, LENGTH, "MessageId");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MessageId that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "MessageId[" + ProtocolBytes.hex(bytes) + "]";
    }
}
