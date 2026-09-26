package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time windows, partial periods, metric provenance, and insufficient-data rules. */
class MetricAndWindowContractTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void dayWindowsAreLocalMidnightToMidnightIncludingDstTransitions() {
        ComparisonWindow normal = ComparisonWindow.day(LocalDate.of(2026, 9, 21), BERLIN);
        assertEquals(Instant.parse("2026-09-20T22:00:00Z"), normal.start());
        assertEquals(Duration.ofHours(24), normal.length());
        assertEquals(Duration.ofHours(23), ComparisonWindow.day(LocalDate.of(2026, 3, 29), BERLIN).length());
        assertEquals(Duration.ofHours(25), ComparisonWindow.day(LocalDate.of(2026, 10, 25), BERLIN).length());
    }

    @Test
    void weekWindowsHonourTheExplicitWeekStart() {
        LocalDate wednesday = LocalDate.of(2026, 9, 23);
        assertEquals(LocalDate.of(2026, 9, 21), ComparisonWindow.week(wednesday, BERLIN, DayOfWeek.MONDAY).localStartDate());
        assertEquals(LocalDate.of(2026, 9, 20), ComparisonWindow.week(wednesday, BERLIN, DayOfWeek.SUNDAY).localStartDate());
        assertEquals(LocalDate.of(2026, 9, 19), ComparisonWindow.week(wednesday, BERLIN, DayOfWeek.SATURDAY).localStartDate());
        ComparisonWindow dstWeek = ComparisonWindow.week(LocalDate.of(2026, 10, 25), BERLIN, DayOfWeek.MONDAY);
        assertEquals(Duration.ofHours(169), dstWeek.length());
    }

    @Test
    void windowsAreHalfOpenAndCutoffsClamp() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 21), ZoneId.of("UTC"));
        assertTrue(day.contains(day.start()));
        assertFalse(day.contains(day.end()));
        assertEquals(day.start(), day.cutoffAt(day.start().minusSeconds(5)));
        assertEquals(day.end(), day.cutoffAt(day.end().plusSeconds(5)));
        assertEquals(day.start().plus(Duration.ofHours(9)), day.equalElapsedCutoff(Duration.ofHours(9)));
        assertEquals(day.end(), day.equalElapsedCutoff(Duration.ofDays(3)));
    }

    @Test
    void sameLocalPeriodComparesLabelsNotInstants() {
        ComparisonWindow berlin = ComparisonWindow.day(LocalDate.of(2026, 9, 21), BERLIN);
        ComparisonWindow tokyo = ComparisonWindow.day(LocalDate.of(2026, 9, 21), ZoneId.of("Asia/Tokyo"));
        assertFalse(berlin.sameLocalPeriodAs(tokyo));
        assertTrue(berlin.sameLocalPeriodAs(ComparisonWindow.day(LocalDate.of(2026, 9, 21), BERLIN)));
    }

    @Test
    void implausibleWindowShapesAreRejected() {
        Instant start = Instant.parse("2026-09-21T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new ComparisonWindow(WindowKind.DAY,
                LocalDate.of(2026, 9, 21), "UTC", null, start, start.plus(Duration.ofHours(30))));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonWindow(WindowKind.DAY,
                LocalDate.of(2026, 9, 21), "UTC", DayOfWeek.MONDAY, start, start.plus(Duration.ofHours(24))));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonWindow(WindowKind.WEEK,
                LocalDate.of(2026, 9, 22), "UTC", DayOfWeek.MONDAY, start, start.plus(Duration.ofDays(7))));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonWindow(WindowKind.DAY,
                LocalDate.of(2026, 9, 21), "../etc/passwd", null, start, start.plus(Duration.ofHours(24))));
    }

    @Test
    void partialPeriodsAreExplicit() {
        ProgressSummary partial = (ProgressSummary) ProtocolFixtures.all().get("progress-summary-day-partial").body();
        ProgressSummary complete = (ProgressSummary) ProtocolFixtures.all().get("progress-summary-week-group").body();
        assertFalse(partial.periodComplete());
        assertTrue(complete.periodComplete());
        assertEquals(SharingScope.DAILY_SUMMARY, partial.requiredScope());
        assertEquals(SharingScope.WEEKLY_SUMMARY, complete.requiredScope());
    }

    @Test
    void verifiedCorrectnessCannotUseTheLegacySelfRatingFallback() {
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.verified_correct_rate", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED, 9000, 50,
                MetricProvenance.LEGACY_SELF_RATING_FALLBACK, TimestampBasis.LEGACY_SQLITE_UTC));
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.verified_correct_rate", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED, 9000, 50,
                MetricProvenance.SELF_RATED, TimestampBasis.EXACT_UTC));
    }

    @Test
    void missingDataIsNeverAZero() {
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.verified_correct_rate", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED, 9000, 3,
                MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.EXACT_UTC), "below minimum sample");
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.verified_correct_rate", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.INSUFFICIENT_DATA, 5000, 3,
                MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.EXACT_UTC), "hidden value");
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.verified_correct_rate", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.INSUFFICIENT_DATA, 0, 30,
                MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.EXACT_UTC), "enough samples");
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("mock.overall_score", 1,
                MetricUnit.PERCENT, MetricAvailability.UNAVAILABLE, 0, 2,
                MetricProvenance.MOCK_SELF_SCORE, TimestampBasis.EXACT_UTC), "unavailable has no samples");
    }

    @Test
    void unitsAndRangesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.attempts", 1,
                MetricUnit.SECONDS, MetricAvailability.MEASURED, 1, 1, MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("future.metric", 1,
                MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED, 10_001, 1, MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        assertThrows(IllegalArgumentException.class, () -> new MetricValue("review.attempts", 1,
                MetricUnit.COUNT, MetricAvailability.MEASURED, -1, 0, MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
    }

    @Test
    void unknownMetricsAreStructurallyValidButNotComparable() {
        MetricValue future = new MetricValue("review.attempts", 2, MetricUnit.COUNT, MetricAvailability.MEASURED, 5, 5,
                MetricProvenance.SELF_RATED, TimestampBasis.EXACT_UTC);
        assertFalse(future.comparable());
        assertTrue(new MetricValue("review.attempts", 1, MetricUnit.COUNT, MetricAvailability.MEASURED, 5, 5,
                MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC).comparable());
    }

    @Test
    void registryIsWellFormed() {
        assertEquals(MetricRegistry.V1.size(), MetricRegistry.V1.stream().map(d -> d.metricId() + "@" + d.version()).distinct().count());
        assertTrue(MetricRegistry.find("review.verified_correct_rate", 1).orElseThrow()
                .allowedProvenance().equals(java.util.Set.of(MetricProvenance.VERIFIED_LOCAL_VALIDATION)));
        assertTrue(MetricRegistry.find("review.attempts", 99).isEmpty());
        assertEquals(List.of(), MetricRegistry.V1.stream().filter(d -> d.localSource().isBlank()).toList());
    }
}
