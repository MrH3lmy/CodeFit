package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * The opponent's one-shot accept/decline of a {@link MatchInvitation}, addressed back to the
 * challenger under the same {@code objectId} (the match id) - a different {@code (author, objectId)}
 * replay stream from the invitation's own, since the opponent is a different author. Control (no
 * required scope): responding to an invitation needs no pre-existing consent.
 *
 * <p>{@code startedAt} is the authoritative start instant both participants converge on: present
 * if and only if {@code accepted}, chosen by the opponent at accept time. The challenger adopts it
 * verbatim rather than computing their own, so both sides' local records always agree exactly - the
 * one rule this feature's own brief requires ("do not let each device independently decide its own
 * match window"). {@code endsAt} is never transmitted: both sides derive it identically as
 * {@code startedAt + duration}, from the challenger's own {@code MatchInvitation.duration}, already
 * known to both before this message exists.
 */
public record MatchResponse(boolean accepted, Instant startedAt) implements MessageBody {

    public MatchResponse {
        if (accepted) {
            Objects.requireNonNull(startedAt, "startedAt is required when accepted.");
            ProtocolTime.toWireMillis(startedAt, "startedAt");
        } else if (startedAt != null) {
            throw new IllegalArgumentException("A declined response cannot carry a startedAt.");
        }
    }

    @Override
    public MessageType type() {
        return MessageType.MATCH_RESPONSE;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        Audience audience = header.audience();
        if (audience.kind() != AudienceKind.DIRECT || audience.recipients().size() != 1) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "A match response is addressed to exactly one DIRECT recipient.");
        }
        if (startedAt != null && startedAt.isAfter(header.createdAt())) {
            throw new IllegalArgumentException("A match cannot be declared started after this response's own creation time.");
        }
    }

    @Override
    public byte[] encodeBody() {
        CanonicalWriter writer = new CanonicalWriter().bool(accepted).bool(startedAt != null);
        if (startedAt != null) {
            writer.i64(startedAt.toEpochMilli());
        }
        return writer.toByteArray();
    }

    static MatchResponse readFrom(CanonicalReader reader) {
        boolean accepted = reader.bool();
        boolean hasStartedAt = reader.bool();
        Instant startedAt = hasStartedAt ? ProtocolTime.fromWireMillis(reader.i64(), "startedAt") : null;
        return new MatchResponse(accepted, startedAt);
    }
}
