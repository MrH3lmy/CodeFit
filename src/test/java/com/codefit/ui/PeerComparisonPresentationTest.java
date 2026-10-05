package com.codefit.ui;

import com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState;
import com.codefit.peer.comparison.SnapshotComparisonEngine.MetricComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ProgressComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.Reason;
import com.codefit.peer.comparison.SnapshotComparisonEngine.SnapshotObservation;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.TimestampBasis;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure coverage of the Peers screen redesign's smart-empty-state and row-formatting rules - no
 * JavaFX toolkit, no database, no identity. {@link ProgressComparison}/{@link MetricComparison} are
 * built directly (never through the full engine or a real capture) so these tests isolate exactly
 * what {@link PeerComparisonPresentation} itself decides, independent of {@code
 * SnapshotComparisonEngine}'s own already-covered comparability logic.
 */
class PeerComparisonPresentationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void rendersHumanLabelsAndFormattedValuesNeverRawIdsOrRawUnits() {
        List<MetricComparison> metrics = List.of(
                comparable("review.attempts", MetricUnit.COUNT, 14L, 9L, 0),
                comparable("problem.solving_seconds", MetricUnit.SECONDS, 2520L, 1680L, 0),
                comparable("review.verified_correct_rate", MetricUnit.BASIS_POINTS, 8600L, 7800L, 10));

        PeerComparisonPresentation.Rendered rendered = PeerComparisonPresentation.present(comparableResult(metrics), "No activity.");

        assertEquals(PeerComparisonPresentation.Kind.ROWS, rendered.kind());
        assertEquals(3, rendered.rows().size());
        for (PeerComparisonPresentation.Row row : rendered.rows()) {
            assertFalse(row.label().contains("."), "a rendered row label must never be a raw dotted metric id: " + row.label());
            assertFalse(row.you().contains("review.") && row.you().contains("problem."), row.you());
        }
        PeerComparisonPresentation.Row reviews = rowFor(rendered, "Reviews");
        assertEquals("14", reviews.you());
        assertEquals("9", reviews.peer());
        PeerComparisonPresentation.Row solvingTime = rowFor(rendered, "Problem-solving time");
        assertEquals("42 min", solvingTime.you());
        assertEquals("28 min", solvingTime.peer());
        PeerComparisonPresentation.Row accuracy = rowFor(rendered, "Review accuracy");
        assertEquals("86%", accuracy.you());
        assertEquals("78%", accuracy.peer());
    }

    @Test
    void allZeroMetricsCollapseToTheEmptyState() {
        List<MetricComparison> metrics = List.of(
                comparable("review.attempts", MetricUnit.COUNT, 0L, 0L, 0),
                comparable("problem.attempts", MetricUnit.COUNT, 0L, 0L, 0));

        PeerComparisonPresentation.Rendered rendered = PeerComparisonPresentation.present(
                comparableResult(metrics), "No study activity recorded by either person yet today.");

        assertEquals(PeerComparisonPresentation.Kind.EMPTY, rendered.kind());
        assertEquals("No study activity recorded by either person yet today.", rendered.message());
        assertTrue(rendered.rows().isEmpty());
    }

    @Test
    void aMeaningfulZeroVersusPositiveRowIsNeverSuppressedEvenAmongAllZeroRows() {
        List<MetricComparison> metrics = List.of(
                comparable("review.attempts", MetricUnit.COUNT, 0L, 0L, 0),      // both-zero: suppressed
                comparable("problem.attempts", MetricUnit.COUNT, 0L, 4L, 0),     // You: 0, Peer: 4 - must stay visible
                comparable("problem.accepted", MetricUnit.COUNT, 5L, 0L, 0));    // You: 5, Peer: 0 - must stay visible

        PeerComparisonPresentation.Rendered rendered = PeerComparisonPresentation.present(comparableResult(metrics), "No activity.");

        assertEquals(PeerComparisonPresentation.Kind.ROWS, rendered.kind());
        assertEquals(2, rendered.rows().size(), "only the two meaningful rows must be shown, not the all-zero one");
        PeerComparisonPresentation.Row zeroVsPositive = rowFor(rendered, "Problems attempted");
        assertEquals("0", zeroVsPositive.you());
        assertEquals("4", zeroVsPositive.peer());
        PeerComparisonPresentation.Row positiveVsZero = rowFor(rendered, "Problems accepted");
        assertEquals("5", positiveVsZero.you());
        assertEquals("0", positiveVsZero.peer());
    }

    @Test
    void aRowWhereOnlyOneSideHasAnyDataIsKeptWithAnEmDashForTheMissingSide() {
        MetricComparison onlyMine = new MetricComparison("problem.unique_completed", ComparisonState.UNAVAILABLE,
                Reason.RIGHT_METRIC_MISSING, Optional.of(measured("problem.unique_completed", MetricUnit.COUNT, 3, 0)),
                Optional.empty(), Optional.empty());

        PeerComparisonPresentation.Rendered rendered = PeerComparisonPresentation.present(
                comparableResult(List.of(onlyMine)), "No activity.");

        assertEquals(PeerComparisonPresentation.Kind.ROWS, rendered.kind());
        PeerComparisonPresentation.Row row = rendered.rows().get(0);
        assertEquals("Problems solved", row.label());
        assertEquals("3", row.you());
        assertEquals("—", row.peer());
    }

    @Test
    void unavailableReasonsProduceShortHumanMessagesNeverTheEngineReasonName() {
        assertEquals("Peer hasn't shared their progress.",
                PeerComparisonPresentation.present(unavailableResult(Reason.RIGHT_NOT_SHARED), "x").message());
        assertEquals("Waiting for peer progress to sync.",
                PeerComparisonPresentation.present(unavailableResult(Reason.RIGHT_MISSING), "x").message());
        assertEquals("Peer progress is too old. Ask them to share again.",
                PeerComparisonPresentation.present(unavailableResult(Reason.RIGHT_STALE), "x").message());

        String cutoffMismatchMessage = PeerComparisonPresentation.present(unavailableResult(Reason.CUTOFF_MISMATCH), "x").message();
        assertFalse(cutoffMismatchMessage.contains("CUTOFF_MISMATCH"));
        assertFalse(cutoffMismatchMessage.contains("_"), "no engine enum constant name (which always has an underscore) may leak: " + cutoffMismatchMessage);

        PeerComparisonPresentation.Rendered incompatible = PeerComparisonPresentation.present(incompatibleResult(), "x");
        assertEquals(PeerComparisonPresentation.Kind.MESSAGE, incompatible.kind());
        assertFalse(incompatible.message().contains("_"), "no engine enum constant name may leak: " + incompatible.message());
    }

    // --- helpers ---

    private static PeerComparisonPresentation.Row rowFor(PeerComparisonPresentation.Rendered rendered, String label) {
        return rendered.rows().stream().filter(row -> row.label().equals(label)).findFirst()
                .orElseGet(() -> fail("no row labelled '" + label + "' among " + rendered.rows()));
    }

    private static MetricComparison comparable(String id, MetricUnit unit, Long leftValue, Long rightValue, long sampleSize) {
        Optional<MetricValue> left = leftValue == null ? Optional.empty() : Optional.of(measured(id, unit, leftValue, sampleSize));
        Optional<MetricValue> right = rightValue == null ? Optional.empty() : Optional.of(measured(id, unit, rightValue, sampleSize));
        return new MetricComparison(id, ComparisonState.COMPARABLE, Reason.NONE, left, right, Optional.empty());
    }

    private static MetricValue measured(String id, MetricUnit unit, long value, long sampleSize) {
        return new MetricValue(id, 1, unit, MetricAvailability.MEASURED, value, sampleSize, provenanceFor(id), TimestampBasis.EXACT_UTC);
    }

    private static MetricProvenance provenanceFor(String id) {
        return switch (id) {
            case "review.attempts" -> MetricProvenance.LOCAL_RECORD;
            case "review.verified_correct_rate" -> MetricProvenance.VERIFIED_LOCAL_VALIDATION;
            case "problem.attempts" -> MetricProvenance.LOCAL_RECORD;
            case "problem.accepted", "problem.unique_completed" -> MetricProvenance.LEARNER_REPORTED_OUTCOME;
            case "problem.solving_seconds" -> MetricProvenance.LOCAL_TIMER;
            case "mock.overall_score" -> MetricProvenance.MOCK_SELF_SCORE;
            default -> MetricProvenance.LOCAL_RECORD;
        };
    }

    private static ProgressComparison comparableResult(List<MetricComparison> metrics) {
        SnapshotObservation<ProgressSummary> observation = dummyObservation();
        return new ProgressComparison(ComparisonState.COMPARABLE, Reason.NONE, observation, observation,
                Optional.empty(), Optional.empty(), false, true, metrics);
    }

    private static ProgressComparison unavailableResult(Reason reason) {
        SnapshotObservation<ProgressSummary> observation = dummyObservation();
        return new ProgressComparison(ComparisonState.UNAVAILABLE, reason, observation, observation,
                Optional.empty(), Optional.empty(), false, false, List.of());
    }

    private static ProgressComparison incompatibleResult() {
        SnapshotObservation<ProgressSummary> observation = dummyObservation();
        return new ProgressComparison(ComparisonState.INCOMPATIBLE, Reason.UTC_INTERVAL_MISMATCH, observation, observation,
                Optional.empty(), Optional.empty(), false, false, List.of());
    }

    /** Only used to satisfy {@code ProgressComparison}'s own structural validity - {@link
     *  PeerComparisonPresentation#present} never reads {@code left}/{@code right} for a
     *  COMPARABLE/DESCRIPTIVE_ONLY/UNAVAILABLE/INCOMPATIBLE result; only {@code state}/{@code reason}/
     *  {@code metrics} drive its decisions. */
    private static SnapshotObservation<ProgressSummary> dummyObservation() {
        ComparisonWindow window = ComparisonWindow.day(LocalDate.now(UTC), UTC);
        MetricValue placeholder = measured("review.attempts", MetricUnit.COUNT, 0, 0);
        ProgressSummary summary = new ProgressSummary(window, window.end(), List.of(placeholder));
        return SnapshotObservation.available(summary, window.end());
    }
}
