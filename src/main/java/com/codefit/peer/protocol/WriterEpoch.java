package com.codefit.peer.protocol;

import java.time.Instant;

/**
 * Writer epochs are wall-clock derived so that a new writer session gets a fresh epoch
 * <em>without</em> trusting any persisted counter. A counter saved in a backup can't guarantee
 * uniqueness: restoring the same backup twice would reuse "backup epoch + 1".
 *
 * <p>An epoch is a second count: {@code 1 + floor((t - 2024-01-01T00:00Z) / 1s)}. An envelope's epoch
 * may not exceed the epoch of its own {@code createdAt} ({@link #maxAt}). That rule stops a writer
 * whose clock ran ahead from issuing an epoch that would lock out every later session. A writer
 * starts a session with {@link #next}, which returns the current second's epoch,
 * {@code maxAt(now)}. It refuses to start when that is not strictly greater than the previous epoch
 * the writer knows. Two sessions started at different seconds on a non-regressing clock therefore
 * never share an epoch, however often one backup is restored.
 * Guarantees and limits are in {@code docs/p2p/protocol-v1.md} §10.
 */
public final class WriterEpoch {
    private static final long MAX_EPOCH = 0xFFFF_FFFFL;

    private WriterEpoch() {
    }

    /** Largest epoch an envelope created at {@code createdAt} may carry. */
    public static long maxAt(Instant createdAt) {
        long millis = ProtocolTime.toWireMillis(createdAt, "createdAt");
        return Math.min(MAX_EPOCH, (millis - ProtocolVersion.MIN_TIMESTAMP_MILLIS) / 1000 + 1);
    }

    /**
     * Epoch for a writer session starting at {@code now}: always the current clock epoch,
     * {@code maxAt(now)}, never {@code previousEpoch + 1}. {@code previousEpoch} is the highest epoch
     * the writer knows (its own stored value or the backup's), or 0 if unknown.
     *
     * @throws IllegalStateException when {@code previousEpoch >= maxAt(now)}: the local clock is at or
     *                               behind an earlier session's second. Publishing must wait until the
     *                               clock passes it, or the clock must be fixed.
     */
    public static long next(long previousEpoch, Instant now) {
        long clockEpoch = maxAt(now);
        if (previousEpoch >= clockEpoch) {
            throw new IllegalStateException("Clock is behind the previous writer epoch " + previousEpoch
                    + "; refusing to reuse or pre-date an epoch.");
        }
        return clockEpoch;
    }
}
