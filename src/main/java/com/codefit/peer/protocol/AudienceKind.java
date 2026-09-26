package com.codefit.peer.protocol;

/** Who an envelope is addressed to. There is intentionally no public/broadcast kind in v1. */
public enum AudienceKind implements WireCode {
    /** One or more individually listed contacts (per-recipient sharing). */
    DIRECT(1),
    /** Members of one private group/challenge, listed explicitly; the group id alone grants nothing. */
    GROUP(2);

    private final int code;

    AudienceKind(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
