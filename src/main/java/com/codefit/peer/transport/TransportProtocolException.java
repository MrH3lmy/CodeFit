package com.codefit.peer.transport;

import java.io.IOException;

/** A handshake or framing failure with a specific, user-facing {@link ConnectionFailureReason}. */
final class TransportProtocolException extends IOException {
    private final ConnectionFailureReason reason;

    TransportProtocolException(ConnectionFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    TransportProtocolException(ConnectionFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    ConnectionFailureReason reason() {
        return reason;
    }
}
