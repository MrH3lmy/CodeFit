package com.codefit.peer.invitation;

/** A candidate invitation blob is malformed, tampered, expired, or otherwise unusable. */
public final class InvitationException extends RuntimeException {
    private final InvitationRejectionReason reason;

    public InvitationException(InvitationRejectionReason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public InvitationRejectionReason reason() {
        return reason;
    }
}
