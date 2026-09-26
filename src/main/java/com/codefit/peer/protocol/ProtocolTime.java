package com.codefit.peer.protocol;

import java.time.Instant;

/** UTC instant rules: whole milliseconds inside the protocol's timestamp bounds. */
final class ProtocolTime {
    private ProtocolTime() {
    }

    static long toWireMillis(Instant instant, String field) {
        if (instant.getNano() % 1_000_000 != 0) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, field + " must have millisecond precision.");
        }
        long millis;
        try {
            millis = instant.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, field + " out of range.");
        }
        if (millis < ProtocolVersion.MIN_TIMESTAMP_MILLIS || millis > ProtocolVersion.MAX_TIMESTAMP_MILLIS) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, field + " outside protocol bounds: " + instant);
        }
        return millis;
    }

    static Instant fromWireMillis(long millis, String field) {
        if (millis < ProtocolVersion.MIN_TIMESTAMP_MILLIS || millis > ProtocolVersion.MAX_TIMESTAMP_MILLIS) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, field + " outside protocol bounds: " + millis);
        }
        return Instant.ofEpochMilli(millis);
    }
}
