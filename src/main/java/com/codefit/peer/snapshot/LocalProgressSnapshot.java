package com.codefit.peer.snapshot;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ProgressSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * #183's own local, dated capture of one {@link ComparisonWindow}: real evidence aggregated into
 * wire-ready {@link MetricValue}s, with the local bookkeeping (a forward-only {@code revision}, and
 * {@code capturedAt}: when this device actually ran the capture, distinct from {@code cutoff}, the
 * data time the metrics describe) that the wire {@link ProgressSummary} itself has no need to carry.
 * Deliberately reuses the protocol's own {@link MetricValue} as the stored representation rather than
 * a second, parallel type: if this captures, it is already wire-valid.
 *
 * <p>This is a <em>local source</em> record, produced from this device's own evidence. It is never the
 * same namespace as a future #184 "received/cached peer snapshot" store — see
 * {@code com.codefit.repository.LocalProgressSnapshotRepository}.
 *
 * @param revision   strictly increasing per logical window (same {@code window} identity); a later
 *                    capture of the same window is a correction that replaces this one, never a second
 *                    additive total. Derived from {@code cutoff}, matching the established pattern in
 *                    {@code com.codefit.peer.transport} rather than a separately persisted counter.
 * @param capturedAt  when this device ran the capture; always {@code >= cutoff}. Local-only metadata:
 *                    not part of the signed {@link ProgressSummary} body (the envelope's own
 *                    {@code createdAt} plays that role on the wire).
 */
public record LocalProgressSnapshot(ComparisonWindow window, Instant cutoff, long revision, Instant capturedAt,
                                     List<MetricValue> metrics) {

    public LocalProgressSnapshot {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("Revision must be >= 1.");
        }
        if (capturedAt.isBefore(cutoff)) {
            throw new IllegalArgumentException("A snapshot cannot be captured before the data it describes.");
        }
        metrics = List.copyOf(metrics);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("A progress snapshot needs at least one metric.");
        }
    }

    /** The revision {@link #capture} derives for a given {@code cutoff}: whole epoch seconds, clamped to the wire range. */
    public static long revisionFor(Instant cutoff) {
        return Math.min(Math.max(cutoff.getEpochSecond(), 1L), 0xFFFF_FFFFL);
    }

    /** The wire body: metrics sorted strictly ascending by {@code (metricId, metricVersion)}, as {@link ProgressSummary} requires. */
    public ProgressSummary toProgressSummary() {
        List<MetricValue> sorted = new ArrayList<>(metrics);
        sorted.sort(Comparator.comparing(MetricValue::metricId).thenComparingInt(MetricValue::metricVersion));
        return new ProgressSummary(window, cutoff, sorted);
    }
}
