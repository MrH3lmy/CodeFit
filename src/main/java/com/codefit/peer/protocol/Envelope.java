package com.codefit.peer.protocol;

import java.util.Objects;

/** An unsigned envelope: authenticated header plus typed body, ready to be signed by its author. */
public record Envelope(EnvelopeHeader header, MessageBody body) {

    public Envelope {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(body, "body");
        body.validateAgainst(header);
    }

    /** Payload bytes up to (not including) the signature. */
    byte[] encodeUnsigned() {
        byte[] bodyBytes = body.encodeBody();
        if (bodyBytes.length > ProtocolVersion.MAX_BODY_BYTES) {
            throw new ProtocolException(RejectionReason.OVERSIZED, "Body exceeds " + ProtocolVersion.MAX_BODY_BYTES + " bytes.");
        }
        CanonicalWriter writer = new CanonicalWriter()
                .u16(header.minorVersion())
                .u8(body.type().code())
                .u16(body.schemaVersion())
                .fixed(header.author().bytes(), IdentityKey.LENGTH)
                .fixed(header.objectId().bytes(), ObjectId.LENGTH)
                .u32(header.epoch())
                .i64(header.sequence())
                .u32(header.revision())
                .i64(header.createdAt().toEpochMilli())
                .i64(header.expiresAt().toEpochMilli());
        header.audience().writeTo(writer);
        writer.lengthPrefixed(bodyBytes, ProtocolVersion.MAX_BODY_BYTES);
        return writer.toByteArray();
    }

    /** Exactly the bytes the author signs: context string, frame major version, unsigned payload. */
    public byte[] signingBytes() {
        byte[] unsigned = encodeUnsigned();
        byte[] out = new byte[ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length + 1 + unsigned.length];
        System.arraycopy(ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT, 0, out, 0, ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length);
        out[ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length] = (byte) ProtocolVersion.MAJOR;
        System.arraycopy(unsigned, 0, out, ProtocolVersion.ENVELOPE_SIGNATURE_CONTEXT.length + 1, unsigned.length);
        return out;
    }
}
