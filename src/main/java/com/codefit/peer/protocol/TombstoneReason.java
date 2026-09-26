package com.codefit.peer.protocol;

/** Why an object was retired. */
public enum TombstoneReason implements WireCode {
    /** Sharing permission withdrawn. */
    REVOKED(1),
    /** The author deleted the object. */
    DELETED(2);

    private final int code;

    TombstoneReason(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
