package com.codefit.peer.match;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;

import java.time.Instant;
import java.util.Objects;

/**
 * This device's own local record of one 1-v-1 Study Match - the single source of truth for both
 * {@link MatchRole}s (a challenger's and an opponent's own rows for the "same" match are each this
 * record, independently persisted, converging on identical {@code startedAt}/{@code endsAt} once
 * accepted). Never itself the comparison: {@code PeerMatchService} reads this to build the
 * {@code ComparisonWindow} both sides evaluate progress against.
 *
 * @param matchId  the challenger-chosen random id; also the wire {@code objectId} for both
 *                 {@code MatchInvitation} (challenger's own stream) and {@code MatchResponse}
 *                 (opponent's own stream)
 * @param acceptedAt set only on {@link MatchStatus#ACTIVE} (never on {@code DECLINED})
 * @param startedAt  set only on {@code ACTIVE}/{@code COMPLETED}
 * @param endsAt     set only on {@code ACTIVE}/{@code COMPLETED}; {@code startedAt + duration}
 */
public record StudyMatch(ObjectId matchId, long contactId, MatchRole role, MatchDuration duration,
                         MatchStatus status, Instant createdAt, Instant acceptedAt, Instant startedAt,
                         Instant endsAt, Instant updatedAt) {

    public StudyMatch {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        boolean timed = status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED;
        if (timed != (startedAt != null) || timed != (endsAt != null)) {
            throw new IllegalArgumentException("startedAt/endsAt must be set if and only if ACTIVE or COMPLETED.");
        }
    }

    /** The in-progress-or-finished comparison window both participants evaluate against. Requires {@link #startedAt}. */
    public ComparisonWindow window() {
        return ComparisonWindow.match(startedAt, endsAt);
    }
}
