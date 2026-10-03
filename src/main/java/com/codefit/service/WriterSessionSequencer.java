package com.codefit.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single, process-wide source of per-author, per-epoch envelope sequence numbers (#180:
 * {@code EnvelopeHeader.sequence} is a "per-author, per-epoch strictly increasing counter").
 *
 * <p>A process signs many different kinds of envelope - progress summaries and preparation snapshots
 * via {@link SnapshotPublicationService}, consent revisions and tombstones via {@link
 * PeerSyncOutboxService} - across many separate calls during the same writer session (epoch). All of
 * them must draw from this one shared counter, never a counter private to whichever service instance
 * happens to be signing: two independently-constructed service instances each starting their own
 * counter at zero would let two innocently-different objects, signed under the same epoch, collide on
 * one {@code (epoch, sequence)} slot - the receiver's {@code EnvelopeAcceptancePolicy} would then
 * reject the second one to arrive as {@code FORKED}, even though both are genuine, unrelated
 * publications from the same author.
 */
final class WriterSessionSequencer {
    private static final Map<Long, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private WriterSessionSequencer() {
    }

    static long next(long epoch) {
        return COUNTERS.computeIfAbsent(epoch, e -> new AtomicLong(0)).incrementAndGet();
    }
}
