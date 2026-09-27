package com.codefit.peer.protocol;

import java.security.PrivateKey;
import java.util.Arrays;

/**
 * Frames, signs, parses, and verifies v1 envelopes. Pure byte-array functions: this class performs no
 * I/O. A future transport (#182) reads the fixed 8-byte header, calls {@link #payloadLength(byte[])}
 * to validate it <em>before</em> reading or buffering the payload, then passes the whole frame to
 * {@link #decodeFrame(byte[])}.
 *
 * <p>Decode order is fixed so every conforming receiver reports the same reason for the same bytes:
 * frame magic → major version → payload bound → exact length → envelope header fields (type,
 * schema, timestamps, audience) → body length bound → signature → body schema → canonical
 * re-encoding.
 */
public final class EnvelopeCodec {

    private EnvelopeCodec() {
    }

    public static SignedEnvelope sign(Envelope envelope, PrivateKey authorPrivateKey) {
        SignedEnvelope signed = new SignedEnvelope(envelope, Ed25519.sign(authorPrivateKey, envelope.signingBytes()));
        if (!signed.verifySignature()) {
            throw new IllegalArgumentException("Private key does not belong to the envelope author.");
        }
        return signed;
    }

    public static byte[] encodeFrame(SignedEnvelope signed) {
        byte[] payload = signed.encodePayload();
        if (payload.length > ProtocolVersion.MAX_FRAME_PAYLOAD_BYTES) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "Frame payload exceeds " + ProtocolVersion.MAX_FRAME_PAYLOAD_BYTES + " bytes.");
        }
        CanonicalWriter writer = new CanonicalWriter()
                .fixed(ProtocolVersion.FRAME_MAGIC, ProtocolVersion.FRAME_MAGIC.length)
                .u8(ProtocolVersion.MAJOR)
                .u32(payload.length);
        byte[] header = writer.toByteArray();
        byte[] frame = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, frame, header.length, payload.length);
        return frame;
    }

    /** Validates a frame header (magic, major version, payload bound) and returns the payload length. */
    public static int payloadLength(byte[] frameHeader) {
        if (frameHeader.length < ProtocolVersion.FRAME_HEADER_BYTES) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Truncated frame header.");
        }
        for (int i = 0; i < ProtocolVersion.FRAME_MAGIC.length; i++) {
            if (frameHeader[i] != ProtocolVersion.FRAME_MAGIC[i]) {
                throw new ProtocolException(RejectionReason.MALFORMED, "Bad frame magic.");
            }
        }
        int major = frameHeader[3] & 0xFF;
        if (major != ProtocolVersion.MAJOR) {
            throw new ProtocolException(RejectionReason.UNSUPPORTED_VERSION, "Unsupported protocol major version " + major);
        }
        CanonicalReader reader = new CanonicalReader(Arrays.copyOfRange(frameHeader, 4, 8));
        long length = reader.u32();
        if (length > ProtocolVersion.MAX_FRAME_PAYLOAD_BYTES) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "Frame payload of " + length + " bytes exceeds the maximum.");
        }
        return (int) length;
    }

    public static SignedEnvelope decodeFrame(byte[] frame) {
        int length = payloadLength(frame);
        if (frame.length != ProtocolVersion.FRAME_HEADER_BYTES + length) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Frame length does not match its header.");
        }
        byte[] payload = Arrays.copyOfRange(frame, ProtocolVersion.FRAME_HEADER_BYTES, frame.length);
        return decodePayload(payload);
    }

    static SignedEnvelope decodePayload(byte[] payload) {
        CanonicalReader reader = new CanonicalReader(payload);
        int minor = reader.u16();
        MessageType type = MessageType.fromWire(reader.u8());
        int schemaVersion = reader.u16();
        if (schemaVersion != 1) {
            throw new ProtocolException(RejectionReason.UNSUPPORTED_SCHEMA_VERSION,
                    type + " schema " + schemaVersion + " is not supported by this build.");
        }
        IdentityKey author = new IdentityKey(reader.fixed(IdentityKey.LENGTH));
        ObjectId objectId = new ObjectId(reader.fixed(ObjectId.LENGTH));
        long epoch = reader.u32();
        long sequence = reader.i64();
        long revision = reader.u32();
        var createdAt = ProtocolTime.fromWireMillis(reader.i64(), "createdAt");
        var expiresAt = ProtocolTime.fromWireMillis(reader.i64(), "expiresAt");
        Audience audience = Audience.readFrom(reader);
        EnvelopeHeader header = new EnvelopeHeader(minor, author, objectId, epoch, sequence, revision, createdAt, expiresAt, audience);
        byte[] bodyBytes = reader.lengthPrefixed(ProtocolVersion.MAX_BODY_BYTES);
        byte[] signature = reader.fixed(Ed25519.SIGNATURE_LENGTH);
        reader.finish();

        int unsignedLength = payload.length - Ed25519.SIGNATURE_LENGTH;
        byte[] signingBytes = new byte[ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length + 1 + unsignedLength];
        System.arraycopy(ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT, 0, signingBytes, 0, ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length);
        signingBytes[ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length] = (byte) ProtocolVersion.MAJOR;
        System.arraycopy(payload, 0, signingBytes, ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length + 1, unsignedLength);
        if (!Ed25519.verify(author, signingBytes, signature)) {
            throw new ProtocolException(RejectionReason.BAD_SIGNATURE, "Signature does not verify for the author key.");
        }

        MessageBody body = MessageBodies.decode(type, bodyBytes);
        Envelope envelope;
        try {
            envelope = new Envelope(header, body);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(RejectionReason.INCONSISTENT_BODY, e.getMessage());
        }
        SignedEnvelope signed = new SignedEnvelope(envelope, signature);
        if (!Arrays.equals(signed.encodePayload(), payload)) {
            throw new ProtocolException(RejectionReason.NON_CANONICAL, "Envelope is not in canonical form.");
        }
        return signed;
    }
}
