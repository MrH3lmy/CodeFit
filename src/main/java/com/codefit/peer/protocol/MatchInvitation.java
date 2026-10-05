package com.codefit.peer.protocol;

import java.util.Objects;

/**
 * The challenger's proposal to start a 1-v-1 Study Match of {@code duration}, addressed to exactly
 * one opponent. The envelope's own {@code objectId} is the match id itself (challenger-chosen,
 * random, collision-resistant - {@code ObjectId} already is exactly this kind of "author-chosen
 * random id of a logical object"), so no separate match-id field is carried here. Control (no
 * required scope): inviting someone to a match needs no pre-existing consent, exactly like a
 * {@link ConsentRevision} needs none to be received. Withdrawing a still-{@code PENDING} invitation
 * is a {@link Tombstone} of this same object - no separate "cancelled" message type exists.
 */
public record MatchInvitation(MatchDuration duration) implements MessageBody {

    public MatchInvitation {
        Objects.requireNonNull(duration, "duration");
    }

    @Override
    public MessageType type() {
        return MessageType.MATCH_INVITATION;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        Audience audience = header.audience();
        if (audience.kind() != AudienceKind.DIRECT || audience.recipients().size() != 1) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "A match invitation is addressed to exactly one DIRECT recipient.");
        }
    }

    @Override
    public byte[] encodeBody() {
        return new CanonicalWriter().u8(duration.code()).toByteArray();
    }

    static MatchInvitation readFrom(CanonicalReader reader) {
        return new MatchInvitation(WireCode.fromCode(MatchDuration.class, reader.u8()));
    }
}
