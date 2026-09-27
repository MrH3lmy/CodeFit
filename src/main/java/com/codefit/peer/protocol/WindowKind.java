package com.codefit.peer.protocol;

/**
 * Calendar period kinds for comparisons. Rolling windows are computed locally, never exchanged in
 * v1.
 */
public enum WindowKind implements WireCode {
    DAY(1),
    WEEK(2);

    private final int code;

    WindowKind(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
