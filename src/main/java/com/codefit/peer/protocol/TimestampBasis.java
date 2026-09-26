package com.codefit.peer.protocol;

/** How exactly the timestamps behind a metric map onto the window's UTC interval. */
public enum TimestampBasis implements WireCode {
    /** Evidence recorded as UTC instants (all new peer-feature data). */
    EXACT_UTC(1),
    /**
     * Legacy column filled by SQLite {@code CURRENT_TIMESTAMP}: UTC to the second, stored without
     * an offset (e.g. {@code review_history.reviewed_at}, {@code problem_attempts.submitted_at}).
     */
    LEGACY_SQLITE_UTC(2),
    /**
     * Legacy column written from Java {@code LocalDateTime.now()} without an offset (e.g. {@code
     * interview_mock_runs.completed_at}, {@code problem_progress.completed_at}); interpreted in the
     * comparison zone, so DST folds and past zone changes make it approximate.
     */
    LEGACY_LOCAL_ASSUMED_ZONE(3),
    /** More than one of the above contributed; treat boundaries as approximate. */
    MIXED(4);

    private final int code;

    TimestampBasis(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
