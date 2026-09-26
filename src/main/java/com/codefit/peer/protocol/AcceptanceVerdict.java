package com.codefit.peer.protocol;

/**
 * Outcome of {@link EnvelopeAcceptancePolicy#evaluate}. {@code reason} is {@code null} exactly when
 * the envelope was accepted and recorded.
 */
public record AcceptanceVerdict(RejectionReason reason, MessageId messageId) {

    public boolean accepted() {
        return reason == null;
    }

    static AcceptanceVerdict accept(MessageId id) {
        return new AcceptanceVerdict(null, id);
    }

    static AcceptanceVerdict reject(RejectionReason reason, MessageId id) {
        return new AcceptanceVerdict(reason, id);
    }
}
