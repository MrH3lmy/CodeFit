package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Selected aggregate metrics for one {@link ComparisonWindow}, computed by the author at {@code cutoff}.
 * A summary with {@code cutoff < window.end()} is a partial period and must be labelled as such; it is
 * only compared with another period at the same elapsed point ({@link ComparisonWindow#equalElapsedCutoff}).
 * Never contains per-item rows, problem identities, answers, or imported text.
 *
 * @param metrics 1..32 values, strictly ascending by {@code (metricId, metricVersion)}
 */
public record ProgressSummary(ComparisonWindow window, Instant cutoff, List<MetricValue> metrics) implements MessageBody {
    static final int MAX_METRICS = 32;

    public ProgressSummary {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cutoff, "cutoff");
        ProtocolTime.toWireMillis(cutoff, "cutoff");
        if (cutoff.isBefore(window.start()) || cutoff.isAfter(window.end())) {
            throw new IllegalArgumentException("Cutoff must lie within [window.start, window.end].");
        }
        metrics = List.copyOf(metrics);
        if (metrics.isEmpty() || metrics.size() > MAX_METRICS) {
            throw new IllegalArgumentException("A summary carries 1.." + MAX_METRICS + " metrics.");
        }
        for (int i = 1; i < metrics.size(); i++) {
            MetricValue previous = metrics.get(i - 1);
            MetricValue current = metrics.get(i);
            int byId = previous.metricId().compareTo(current.metricId());
            if (byId > 0 || (byId == 0 && previous.metricVersion() >= current.metricVersion())) {
                throw new ProtocolException(RejectionReason.NON_CANONICAL, "Metrics must be strictly ascending by (id, version).");
            }
        }
    }

    public boolean periodComplete() {
        return cutoff.equals(window.end());
    }

    @Override
    public MessageType type() {
        return MessageType.PROGRESS_SUMMARY;
    }

    @Override
    public SharingScope requiredScope() {
        return window.kind() == WindowKind.DAY ? SharingScope.DAILY_SUMMARY : SharingScope.WEEKLY_SUMMARY;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        if (cutoff.isAfter(header.createdAt())) {
            throw new IllegalArgumentException("A summary cannot describe data after its own creation time.");
        }
    }

    @Override
    public byte[] encodeBody() {
        CanonicalWriter writer = new CanonicalWriter();
        window.writeTo(writer);
        writer.i64(cutoff.toEpochMilli());
        writer.list(metrics, MAX_METRICS, (w, metric) -> metric.writeTo(w));
        return writer.toByteArray();
    }

    static ProgressSummary readFrom(CanonicalReader reader) {
        ComparisonWindow window = ComparisonWindow.readFrom(reader);
        Instant cutoff = ProtocolTime.fromWireMillis(reader.i64(), "cutoff");
        return new ProgressSummary(window, cutoff, reader.list(MAX_METRICS, MetricValue::readFrom));
    }
}
