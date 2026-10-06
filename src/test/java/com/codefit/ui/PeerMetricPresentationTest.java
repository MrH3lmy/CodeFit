package com.codefit.ui;

import com.codefit.peer.protocol.MetricUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pure formatting coverage - no JavaFX toolkit, no database, no identity - for exactly what the
 * Peers screen redesign requires: every metric id CodeFit currently defines gets a human label
 * (never its raw dotted id), and every {@link MetricUnit} is formatted the way a learner expects
 * (seconds as human duration, basis points/percent as a whole percentage), never as a raw wire
 * integer.
 */
class PeerMetricPresentationTest {

    // --- human-readable labels: every metric MetricRegistry currently defines ---

    @Test
    void everyKnownMetricIdGetsAHumanReadableLabelNeverTheRawId() {
        assertEquals("Reviews", PeerMetricPresentation.label("review.attempts"));
        assertEquals("Review accuracy", PeerMetricPresentation.label("review.verified_correct_rate"));
        assertEquals("Self-rated success", PeerMetricPresentation.label("review.self_rated_success_rate"));
        assertEquals("Hint-free reviews", PeerMetricPresentation.label("review.hint_free_rate"));
        assertEquals("Legacy review success", PeerMetricPresentation.label("review.legacy_rating_fallback_success_rate"));
        assertEquals("Problems attempted", PeerMetricPresentation.label("problem.attempts"));
        assertEquals("Problems accepted", PeerMetricPresentation.label("problem.accepted"));
        assertEquals("Problems solved", PeerMetricPresentation.label("problem.unique_completed"));
        assertEquals("Problem-solving time", PeerMetricPresentation.label("problem.solving_seconds"));
        assertEquals("Mock interview score", PeerMetricPresentation.label("mock.overall_score"));
    }

    @Test
    void anUnknownMetricIdStillGetsAReadableLabelNeverTheRawDottedId() {
        String label = PeerMetricPresentation.label("future.new_metric_id");
        assertEquals("Future New Metric Id", label);
        assertFalse(label.contains("."), "a humanized fallback label must never contain the raw id's own dots");
        assertFalse(label.contains("_"), "a humanized fallback label must never contain the raw id's own underscores");
    }

    // --- unit-aware value formatting ---

    @Test
    void countIsFormattedAsAPlainInteger() {
        assertEquals("14", PeerMetricPresentation.formatValue(MetricUnit.COUNT, 14));
        assertEquals("0", PeerMetricPresentation.formatValue(MetricUnit.COUNT, 0));
    }

    @Test
    void secondsAreFormattedAsHumanReadableDuration() {
        assertEquals("45 sec", PeerMetricPresentation.formatSeconds(45));
        assertEquals("8 min", PeerMetricPresentation.formatSeconds(480));
        assertEquals("1h 12m", PeerMetricPresentation.formatSeconds(4320));
        assertEquals("2h", PeerMetricPresentation.formatSeconds(7200), "a whole number of hours must omit a trailing '0m'");
    }

    @Test
    void secondsUnitRoutesThroughTheSameHumanDurationFormatting() {
        assertEquals("1h 12m", PeerMetricPresentation.formatValue(MetricUnit.SECONDS, 4320));
    }

    @Test
    void basisPointsAreFormattedAsAWholeNumberPercentage() {
        assertEquals("85%", PeerMetricPresentation.formatValue(MetricUnit.BASIS_POINTS, 8500));
        assertEquals("0%", PeerMetricPresentation.formatValue(MetricUnit.BASIS_POINTS, 0));
        assertEquals("100%", PeerMetricPresentation.formatValue(MetricUnit.BASIS_POINTS, 10_000));
        // Never a raw basis-points integer like "8567" - always a rounded whole percentage.
        assertEquals("86%", PeerMetricPresentation.formatValue(MetricUnit.BASIS_POINTS, 8567));
    }

    @Test
    void percentIsFormattedDirectlyAsAPercentage() {
        assertEquals("72%", PeerMetricPresentation.formatValue(MetricUnit.PERCENT, 72));
    }

    // --- cutoff formatting: always from the supplied instant, never the system clock ---

    @Test
    void cutoffIsFormattedFromTheSuppliedInstantNeverFromTheSystemClock() {
        Instant fixedCutoff = Instant.parse("2020-01-01T17:37:00Z");
        String formatted = PeerMetricPresentation.formatCutoff(fixedCutoff, ZoneId.of("UTC"));
        assertEquals("5:37 PM", formatted);

        // A second, much later fixed instant must format differently - proving this is a pure
        // function of its own input, not a read of the real current wall-clock time.
        Instant anotherFixedCutoff = Instant.parse("2031-06-15T08:05:00Z");
        assertEquals("8:05 AM", PeerMetricPresentation.formatCutoff(anotherFixedCutoff, ZoneId.of("UTC")));
    }
}
