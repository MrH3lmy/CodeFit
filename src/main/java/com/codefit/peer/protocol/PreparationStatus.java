package com.codefit.peer.protocol;

/**
 * Wire mirror of {@code InterviewReadinessStatus}; decoupled so the wire code never changes if the
 * service enum is renamed.
 */
public enum PreparationStatus implements WireCode {
    READY(1),
    NOT_READY(2),
    INSUFFICIENT_DATA(3);

    private final int code;

    PreparationStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
