package com.codefit.peer.protocol;

/** Wire mirror of {@code InterviewDomainReadinessStatus}, with the same meaning per value. */
public enum DomainStatus implements WireCode {
    /** Critical gate at/above threshold with full coverage. */
    PASS(1),
    /** Critical gate measurably below threshold. */
    FAIL(2),
    /** Critical gate with a score but incomplete coverage; never treated as passed. */
    PARTIAL(3),
    /** Non-critical domain with a score. */
    MEASURED(4),
    /** No measurable requirement. */
    NOT_MEASURED(5);

    private final int code;

    DomainStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
