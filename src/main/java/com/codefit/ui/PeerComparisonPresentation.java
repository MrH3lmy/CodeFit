package com.codefit.ui;

import com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState;
import com.codefit.peer.comparison.SnapshotComparisonEngine.MetricComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ProgressComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.Reason;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure presentation logic turning a real {@link ProgressComparison} - this codebase's own, unmodified
 * comparison result, from either "Compare Today" or an active/completed Study Match - into exactly what
 * the Peers screen should show a learner: human-readable metric rows, a polished "nothing to show yet"
 * empty state, or a short human message for an unavailable/incompatible comparison. Never a new
 * comparison, scoring, or "who is ahead" decision of its own - every one of those stays exactly where it
 * already lives ({@code SnapshotComparisonEngine}/{@code PeerMatchService}). Deliberately has no
 * JavaFX dependency so it is unit-testable directly.
 *
 * <h2>Smart empty state</h2>
 * A metric row is "meaningful" - and therefore shown - when either side has a non-zero measured
 * value <em>or</em> a measured zero backed by real samples. This preserves both "You: 0, Peer: 4" and
 * genuine zero outcomes such as 0% accuracy over ten reviews. Only the truly empty shape
 * ({@code MEASURED}, value 0, sample size 0 on both sides) is suppressible; if suppressing leaves no
 * rows, the section collapses to one short empty-state sentence instead of a wall of "0 vs 0" rows.
 */
public final class PeerComparisonPresentation {

    private PeerComparisonPresentation() {
    }

    /** Which shape the caller should render: a real table of rows, the empty state, or a short message. */
    public enum Kind {
        ROWS, EMPTY, MESSAGE
    }

    /** One renderable comparison row - already human-readable, never a raw metric id or raw unit value. */
    public record Row(String label, String you, String peer) {
        public Row {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(you, "you");
            Objects.requireNonNull(peer, "peer");
        }
    }

    /**
     * The one thing a caller needs to render. {@code rows} is non-empty only for {@link Kind#ROWS};
     * {@code message} is non-null only for {@link Kind#EMPTY}/{@link Kind#MESSAGE}.
     */
    public record Rendered(Kind kind, List<Row> rows, String message) {
        static Rendered rows(List<Row> rows) {
            return new Rendered(Kind.ROWS, List.copyOf(rows), null);
        }

        static Rendered empty(String message) {
            return new Rendered(Kind.EMPTY, List.of(), message);
        }

        static Rendered message(String message) {
            return new Rendered(Kind.MESSAGE, List.of(), message);
        }
    }

    /**
     * @param noActivityMessage the empty-state sentence to show when every metric row is non-meaningful
     *                          (callers vary this slightly between "Compare Today" and Study Match copy)
     */
    public static Rendered present(ProgressComparison comparison, String noActivityMessage) {
        return present(comparison, noActivityMessage, null);
    }

    /**
     * As {@link #present(ProgressComparison, String)}, but unavailable-state messages name the peer
     * ("Waiting for Ahmed's progress…") instead of a generic "peer". {@code peerName} may be {@code null}.
     */
    public static Rendered present(ProgressComparison comparison, String noActivityMessage, String peerName) {
        return switch (comparison.state()) {
            case UNAVAILABLE -> Rendered.message(unavailableMessage(comparison.reason(), peerName));
            case INCOMPATIBLE -> Rendered.message(incompatibleMessage());
            case COMPARABLE, DESCRIPTIVE_ONLY -> presentMetrics(comparison, noActivityMessage);
        };
    }

    private static Rendered presentMetrics(ProgressComparison comparison, String noActivityMessage) {
        List<Row> rows = new ArrayList<>();
        for (MetricComparison metric : comparison.metrics()) {
            if (!isMeaningful(metric.left()) && !isMeaningful(metric.right())) {
                continue;
            }
            rows.add(new Row(PeerMetricPresentation.label(metric.metricId()),
                    describeSide(metric.left()), describeSide(metric.right())));
        }
        return rows.isEmpty() ? Rendered.empty(noActivityMessage) : Rendered.rows(rows);
    }

    private static boolean isMeaningful(Optional<MetricValue> value) {
        if (value.isEmpty() || value.get().availability() != MetricAvailability.MEASURED) {
            return false;
        }
        MetricValue measured = value.get();
        // A measured zero can still be real evidence: 0% accuracy over 10 reviews, a 0% mock score,
        // or 0 seconds over a recorded attempt are all meaningful outcomes and must never collapse
        // into the "no activity" state. Count-style no-activity rows naturally carry both value=0 and
        // sampleSize=0, so only that genuinely empty shape is suppressible.
        return measured.value() != 0 || measured.sampleSize() > 0;
    }

    private static String describeSide(Optional<MetricValue> value) {
        if (value.isEmpty() || value.get().availability() != MetricAvailability.MEASURED) {
            return "—"; // em dash: "no data", never a bare raw 0 standing in for "not shared/measured"
        }
        MetricValue measured = value.get();
        return PeerMetricPresentation.formatValue(measured.unit(), measured.value());
    }

    /**
     * Short, human copy for a whole-comparison {@code UNAVAILABLE} result - never the engine's own
     * {@link Reason} enum name. Reasons this device's own side can report ({@code LEFT_*}) fall back to
     * the same generic message as any other reason not explicitly called out, since they are not
     * actionable guidance for the peer the way the {@code RIGHT_*} reasons are.
     */
    private static String unavailableMessage(Reason reason, String peerName) {
        boolean named = peerName != null && !peerName.isBlank();
        return switch (reason) {
            case RIGHT_NOT_SHARED -> PeerNamePresentation.capitalize(named ? peerName : "Peer") + " hasn't shared their progress.";
            case RIGHT_MISSING -> named ? "Waiting for " + peerName + "'s progress…" : "Waiting for peer progress to sync.";
            case RIGHT_STALE -> PeerNamePresentation.capitalize(named ? peerName + "'s" : "Peer") + " progress is too old. Ask them to share again.";
            case CUTOFF_MISMATCH, COMPLETE_PARTIAL_MISMATCH -> incompatibleMessage();
            default -> "Comparison isn't available right now.";
        };
    }

    private static String incompatibleMessage() {
        return "This comparison isn't available for these two time windows.";
    }
}
