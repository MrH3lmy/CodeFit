package com.codefit.peer.protocol;

/** Unit of a {@link MetricValue}'s integer value; ranges are enforced on encode and decode. */
public enum MetricUnit implements WireCode {
    /** Non-negative count. */
    COUNT(1),
    /** A rate in 0..10000 (1 bp = 0.01%), exact integer arithmetic. */
    BASIS_POINTS(2),
    /** Whole percentage points 0..100 (matches readiness rounding). */
    PERCENT(3),
    /** Non-negative duration in seconds. */
    SECONDS(4);

    private final int code;

    MetricUnit(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
