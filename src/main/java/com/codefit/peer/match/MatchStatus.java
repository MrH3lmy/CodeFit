package com.codefit.peer.match;

/**
 * A {@link StudyMatch}'s lifecycle state. {@code PENDING} is the only non-terminal state that can
 * still move; every other state is terminal from the local device's own point of view and must
 * never regress (an older/replayed/duplicate incoming message that would imply otherwise is
 * ignored - see {@code MatchRepository}).
 */
public enum MatchStatus {
    /** Invitation sent (challenger) or received (opponent); awaiting the opponent's response. */
    PENDING,
    /** Accepted; {@code startedAt}/{@code endsAt} are set and shared study evidence counts. */
    ACTIVE,
    /** {@code endsAt} has passed; the final result is stable. */
    COMPLETED,
    /** The opponent declined. */
    DECLINED,
    /** The challenger withdrew the invitation before any response. */
    CANCELLED
}
