package com.codefit.peer.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Appends v1 canonical primitives: big-endian fixed-width integers, one-byte booleans/presence flags,
 * {@code u16}-length-prefixed UTF-8 strings, and fixed-length byte fields. There is exactly one encoding
 * per value, which is what makes signing bytes and golden fixtures deterministic.
 *
 * <p>The writer validates bounds it can see locally so an invalid value fails at the author, not at
 * every receiver; semantic validation lives in each value type's constructor.
 */
final class CanonicalWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    CanonicalWriter u8(int value) {
        checkRange(value, 0, 0xFF, "u8");
        out.write(value);
        return this;
    }

    CanonicalWriter u16(int value) {
        checkRange(value, 0, 0xFFFF, "u16");
        out.write(value >>> 8);
        out.write(value);
        return this;
    }

    CanonicalWriter u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("u32 out of range: " + value);
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
        return this;
    }

    CanonicalWriter i64(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
        return this;
    }

    CanonicalWriter bool(boolean value) {
        out.write(value ? 1 : 0);
        return this;
    }

    CanonicalWriter fixed(byte[] bytes, int expectedLength) {
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("Expected " + expectedLength + " bytes, got " + bytes.length);
        }
        out.writeBytes(bytes);
        return this;
    }

    CanonicalWriter string(String value, int maxBytes) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxBytes) {
            throw new IllegalArgumentException("String exceeds " + maxBytes + " UTF-8 bytes.");
        }
        u16(utf8.length);
        out.writeBytes(utf8);
        return this;
    }

    CanonicalWriter optionalU8(Integer value) {
        bool(value != null);
        if (value != null) {
            u8(value);
        }
        return this;
    }

    CanonicalWriter optionalString(String value, int maxBytes) {
        bool(value != null);
        if (value != null) {
            string(value, maxBytes);
        }
        return this;
    }

    CanonicalWriter lengthPrefixed(byte[] bytes, int maxBytes) {
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Length-prefixed field exceeds " + maxBytes + " bytes.");
        }
        u32(bytes.length);
        out.writeBytes(bytes);
        return this;
    }

    <T> CanonicalWriter list(List<T> items, int maxCount, ItemWriter<T> itemWriter) {
        if (items.size() > maxCount) {
            throw new IllegalArgumentException("List exceeds " + maxCount + " items.");
        }
        u16(items.size());
        for (T item : items) {
            itemWriter.write(this, item);
        }
        return this;
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private static void checkRange(int value, int min, int max, String type) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(type + " out of range: " + value);
        }
    }

    @FunctionalInterface
    interface ItemWriter<T> {
        void write(CanonicalWriter writer, T item);
    }
}
