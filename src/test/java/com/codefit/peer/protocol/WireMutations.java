package com.codefit.peer.protocol;

import java.util.Arrays;

/**
 * Builds deliberately invalid frames for rejection tests. {@link #resign} re-signs mutated bytes with
 * the fixture author key so each case exercises the intended check instead of failing on the signature.
 */
final class WireMutations {
    static final int TYPE_OFFSET = 2;
    static final int SCHEMA_OFFSET = 3;
    static final int CREATED_OFFSET = 69;
    static final int EXPIRES_OFFSET = 77;
    static final int AUDIENCE_OFFSET = 85;

    private WireMutations() {
    }

    static byte[] unsignedPayload(String fixture) {
        byte[] frame = ProtocolFixtures.readGolden(fixture);
        return Arrays.copyOfRange(frame, ProtocolVersion.FRAME_HEADER_BYTES, frame.length - Ed25519.SIGNATURE_LENGTH);
    }

    static byte[] resign(byte[] unsigned) {
        byte[] context = ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT;
        byte[] signing = new byte[context.length + 1 + unsigned.length];
        System.arraycopy(context, 0, signing, 0, context.length);
        signing[context.length] = (byte) ProtocolVersion.MAJOR;
        System.arraycopy(unsigned, 0, signing, context.length + 1, unsigned.length);
        byte[] signature = Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.AUTHOR_SEED), signing);
        byte[] payload = Arrays.copyOf(unsigned, unsigned.length + signature.length);
        System.arraycopy(signature, 0, payload, unsigned.length, signature.length);
        return frame(payload);
    }

    static byte[] frame(byte[] payload) {
        byte[] header = new CanonicalWriter().fixed(new byte[] {0x43, 0x46, 0x50}, 3).u8(ProtocolVersion.MAJOR)
                .u32(payload.length).toByteArray();
        byte[] frame = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, frame, header.length, payload.length);
        return frame;
    }

    /** An unsigned payload with an arbitrary (possibly invalid) type, schema, and body under a valid header. */
    static byte[] withBody(EnvelopeHeader header, int typeCode, int schemaVersion, byte[] body) {
        CanonicalWriter writer = new CanonicalWriter()
                .u16(header.minorVersion()).u8(typeCode).u16(schemaVersion)
                .fixed(header.author().bytes(), IdentityKey.LENGTH)
                .fixed(header.objectId().bytes(), ObjectId.LENGTH)
                .u32(header.epoch()).i64(header.sequence()).u32(header.revision())
                .i64(header.createdAt().toEpochMilli()).i64(header.expiresAt().toEpochMilli());
        header.audience().writeTo(writer);
        writer.lengthPrefixed(body, Integer.MAX_VALUE);
        return writer.toByteArray();
    }

    static void putLong(byte[] bytes, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            bytes[offset + i] = (byte) value;
            value >>>= 8;
        }
    }
}
