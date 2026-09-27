package com.codefit.peer.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict reader for {@link CanonicalWriter} output. Every length is checked against its bound
 * <em>before</em> allocation, booleans/presence flags must be exactly 0 or 1, UTF-8 must be well
 * formed, and {@link #finish()} rejects trailing bytes - so a successfully read value has exactly one
 * byte representation.
 */
final class CanonicalReader {
    private final byte[] data;
    private int position;

    CanonicalReader(byte[] data) {
        this.data = data;
    }

    int u8() {
        require(1);
        return data[position++] & 0xFF;
    }

    int u16() {
        require(2);
        int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    long u32() {
        require(4);
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        return value;
    }

    long i64() {
        require(8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        return value;
    }

    boolean bool() {
        int value = u8();
        if (value > 1) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Boolean/presence byte must be 0 or 1, got " + value);
        }
        return value == 1;
    }

    byte[] fixed(int length) {
        require(length);
        byte[] copy = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return copy;
    }

    String string(int maxBytes) {
        int length = u16();
        if (length > maxBytes) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "String of " + length + " bytes exceeds " + maxBytes);
        }
        require(length);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, position, length))
                    .toString();
            position += length;
            return value;
        } catch (CharacterCodingException e) {
            throw new ProtocolException(RejectionReason.MALFORMED, "String is not well-formed UTF-8.");
        }
    }

    Integer optionalU8() {
        return bool() ? u8() : null;
    }

    String optionalString(int maxBytes) {
        return bool() ? string(maxBytes) : null;
    }

    byte[] lengthPrefixed(int maxBytes) {
        long length = u32();
        if (length > maxBytes) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "Field of " + length + " bytes exceeds " + maxBytes);
        }
        return fixed((int) length);
    }

    <T> List<T> list(int maxCount, ItemReader<T> itemReader) {
        int count = u16();
        if (count > maxCount) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "List of " + count + " items exceeds " + maxCount);
        }
        List<T> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(itemReader.read(this));
        }
        return List.copyOf(items);
    }

    void finish() {
        if (position != data.length) {
            throw new ProtocolException(RejectionReason.MALFORMED, (data.length - position) + " trailing byte(s).");
        }
    }

    private void require(int length) {
        if (length < 0 || data.length - position < length) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Truncated input.");
        }
    }

    @FunctionalInterface
    interface ItemReader<T> {
        T read(CanonicalReader reader);
    }
}
