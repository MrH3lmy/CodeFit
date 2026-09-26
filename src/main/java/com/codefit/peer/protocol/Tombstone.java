package com.codefit.peer.protocol;

import java.util.Objects;

/**
 * Retires the object named by the envelope's {@code objectId}; the envelope revision must exceed every
 * revision the author ever issued for it. Receivers drop the object's cached copy when
 * {@code requestCacheDeletion} is set and afterwards reject every revision of it as
 * {@link RejectionReason#TOMBSTONED}. This is cooperative deletion: a peer that ignores it cannot be
 * forced, and the UI must not promise otherwise.
 *
 * @param targetType the retired object's message type; consent is revoked with an empty
 *                   {@link ConsentRevision}, and tombstones are not themselves tombstoned
 */
public record Tombstone(MessageType targetType, TombstoneReason reason, boolean requestCacheDeletion)
        implements MessageBody {

    public Tombstone {
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(reason, "reason");
        if (targetType == MessageType.TOMBSTONE || targetType == MessageType.CONSENT_REVISION
                || !targetType.implementedInV1_0()) {
            throw new IllegalArgumentException(targetType + " cannot be tombstoned.");
        }
    }

    @Override
    public MessageType type() {
        return MessageType.TOMBSTONE;
    }

    @Override
    public byte[] encodeBody() {
        return new CanonicalWriter()
                .u8(targetType.code())
                .u8(reason.code())
                .bool(requestCacheDeletion)
                .toByteArray();
    }

    static Tombstone readFrom(CanonicalReader reader) {
        int targetCode = reader.u8();
        MessageType target;
        try {
            target = MessageType.fromWire(targetCode);
        } catch (ProtocolException e) {
            throw new ProtocolException(RejectionReason.INCONSISTENT_BODY, "Tombstone targets unknown type " + targetCode);
        }
        return new Tombstone(target, WireCode.fromCode(TombstoneReason.class, reader.u8()), reader.bool());
    }
}
