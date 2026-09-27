package com.codefit.peer.protocol;

/** A frame, envelope, or body that must be rejected, with a stable machine-readable reason. */
public final class ProtocolException extends RuntimeException {
    private final RejectionReason reason;

    public ProtocolException(RejectionReason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
