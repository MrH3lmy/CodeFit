package com.codefit.peer.protocol;

import java.util.Objects;

/**
 * A cutoff for the object named by the envelope's {@code objectId}. The envelope's
 * {@code (epoch, revision)} must be newer than every earlier version of the object. Receivers delete
 * their cached copy when {@code requestCacheDeletion} is set. After that they reject every version at
 * or below the cutoff as {@link RejectionReason#TOMBSTONED}, so replayed pre-revocation copies stay
 * dead. A strictly newer version, published only once the author shares again, starts a new
 * incarnation. For example, a profile card published after consent is re-granted is accepted.
 *
 * <p>This is cooperative deletion. A peer that ignores it cannot be forced to comply, and the UI must
 * not claim otherwise.
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
