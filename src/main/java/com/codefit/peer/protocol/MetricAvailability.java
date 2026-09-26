package com.codefit.peer.protocol;

/** Whether a metric value exists. Missing data is explicit, never a zero. */
public enum MetricAvailability implements WireCode {
    /** Enough samples; {@code value} is meaningful. */
    MEASURED(1),
    /** Some samples, fewer than the metric's minimum; {@code value} must be 0 and is not displayed. */
    INSUFFICIENT_DATA(2),
    /** Not known for this window (e.g. history before snapshots existed); never back-filled. */
    UNAVAILABLE(3);

    private final int code;

    MetricAvailability(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
