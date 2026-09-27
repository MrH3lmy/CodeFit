package com.codefit.peer.comparison;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.DomainSnapshot;
import com.codefit.peer.protocol.DomainStatus;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationStatus;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.protocol.WindowKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState.COMPARABLE;
import static com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState.DESCRIPTIVE_ONLY;
import static com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState.INCOMPATIBLE;
import static com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotComparisonEngineTest {

    private static final Instant EVALUATION = Instant.parse("2026-09-28T12:00:00Z");
    private static final SnapshotComparisonEngine.EvaluationContext LIVE =
            new SnapshotComparisonEngine.EvaluationContext(EVALUATION, Duration.ofDays(2));
    private static final SnapshotComparisonEngine.EvaluationContext HISTORY =
            new SnapshotComparisonEngine.EvaluationContext(EVALUATION, Duration.ofDays(400));

    @Test
    void sameDayPeerComparisonPreservesMetadataAndUsesMeaningfulDeltas() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 27), ZoneId.of("Europe/Berlin"));
        Instant cutoff = day.start().plus(Duration.ofHours(10));
        ProgressSummary left = summary(day, cutoff,
                metric("problem.accepted", 1, MetricUnit.COUNT, 4, 4,
                        MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.EXACT_UTC),
                metric("review.verified_correct_rate", 1, MetricUnit.BASIS_POINTS, 8500, 20,
                        MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.LEGACY_SQLITE_UTC));
        ProgressSummary right = summary(day, cutoff,
                metric("problem.accepted", 1, MetricUnit.COUNT, 2, 2,
                        MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.EXACT_UTC),
                metric("review.verified_correct_rate", 1, MetricUnit.BASIS_POINTS, 8000, 20,
                        MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.LEGACY_SQLITE_UTC));

        var result = SnapshotComparisonEngine.comparePeerProgress(
                observed(left, EVALUATION.minus(Duration.ofMinutes(10))),
                observed(right, EVALUATION.minus(Duration.ofMinutes(5))), LIVE);

        assertEquals(COMPARABLE, result.state());
        assertTrue(result.equalElapsed());
        assertFalse(result.crossZoneOrPeriod());
        assertEquals(Duration.between(cutoff, EVALUATION), result.leftSnapshotAge().orElseThrow());
        assertEquals(Duration.between(cutoff, EVALUATION), result.rightSnapshotAge().orElseThrow());
        assertEquals(List.of("problem.accepted", "review.verified_correct_rate"),
                result.metrics().stream().map(SnapshotComparisonEngine.MetricComparison::metricId).toList());

        var accepted = result.metrics().get(0);
        assertEquals(new BigDecimal("2"), accepted.delta().orElseThrow().absoluteChange());
        assertBigDecimal("100", accepted.delta().orElseThrow().relativePercentChange().orElseThrow());
        assertEquals(MetricProvenance.LEARNER_REPORTED_OUTCOME, accepted.left().orElseThrow().provenance());
        assertEquals(TimestampBasis.EXACT_UTC, accepted.left().orElseThrow().timestampBasis());

        var rate = result.metrics().get(1);
        assertBigDecimal("5.00", rate.delta().orElseThrow().absoluteChange());
        assertEquals(SnapshotComparisonEngine.DeltaKind.PERCENTAGE_POINTS,
                rate.delta().orElseThrow().kind());
        assertEquals(20, rate.left().orElseThrow().sampleSize());
        assertEquals(TimestampBasis.LEGACY_SQLITE_UTC, rate.left().orElseThrow().timestampBasis());

        var repeated = SnapshotComparisonEngine.comparePeerProgress(
                observed(left, EVALUATION.minus(Duration.ofMinutes(10))),
                observed(right, EVALUATION.minus(Duration.ofMinutes(5))), LIVE);
        assertEquals(result, repeated);
    }

    @Test
    void laterCutoffAggregateCannotBeProratedBackToEarlierCutoff() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 27), ZoneId.of("Europe/Berlin"));
        ProgressSummary left = summary(day, day.start().plus(Duration.ofHours(10)),
                metric("review.attempts", 1, MetricUnit.COUNT, 10, 10,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        ProgressSummary later = summary(day, day.start().plus(Duration.ofHours(12)),
                metric("review.attempts", 1, MetricUnit.COUNT, 14, 14,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));

        var result = SnapshotComparisonEngine.comparePeerProgress(
                observed(left, EVALUATION), observed(later, EVALUATION), LIVE);

        assertEquals(UNAVAILABLE, result.state());
        assertEquals(SnapshotComparisonEngine.Reason.CUTOFF_MISMATCH, result.reason());
        assertFalse(result.equalElapsed());
        assertTrue(result.metrics().isEmpty());

        var completeVsPartial = SnapshotComparisonEngine.comparePeerProgress(
                observed(summary(day, day.end(), attempts(20)), EVALUATION),
                observed(left, EVALUATION), LIVE);
        assertEquals(UNAVAILABLE, completeVsPartial.state());
        assertEquals(SnapshotComparisonEngine.Reason.COMPLETE_PARTIAL_MISMATCH, completeVsPartial.reason());
    }

    @Test
    void historicalSelfSupportsRealDstDaysAndEqualElapsedPartials() {
        ZoneId newYork = ZoneId.of("America/New_York");
        ComparisonWindow dstDay = ComparisonWindow.day(LocalDate.of(2026, 3, 8), newYork);
        ComparisonWindow previousDay = ComparisonWindow.day(LocalDate.of(2026, 3, 7), newYork);
        assertEquals(Duration.ofHours(23), dstDay.length());
        assertEquals(Duration.ofHours(24), previousDay.length());

        var complete = SnapshotComparisonEngine.compareHistoricalSelfProgress(
                observed(summary(dstDay, dstDay.end(), attempts(20)), Instant.parse("2026-03-09T04:10:00Z")),
                observed(summary(previousDay, previousDay.end(), attempts(18)), Instant.parse("2026-03-08T05:10:00Z")),
                HISTORY);
        assertEquals(COMPARABLE, complete.state());
        assertTrue(complete.equalElapsed());

        Duration elapsed = Duration.ofHours(10);
        var partial = SnapshotComparisonEngine.compareHistoricalSelfProgress(
                observed(summary(dstDay, dstDay.start().plus(elapsed), attempts(8)), Instant.parse("2026-03-08T16:00:00Z")),
                observed(summary(previousDay, previousDay.start().plus(elapsed), attempts(7)), Instant.parse("2026-03-07T16:00:00Z")),
                HISTORY);
        assertEquals(COMPARABLE, partial.state());
        assertTrue(partial.equalElapsed());

        ComparisonWindow fallBackDay = ComparisonWindow.day(LocalDate.of(2026, 11, 1), newYork);
        assertEquals(Duration.ofHours(25), fallBackDay.length());
    }

    @Test
    void differentZonesWeekPoliciesAndAuthoritativeIntervalsAreExplicit() {
        LocalDate date = LocalDate.of(2026, 9, 27);
        ComparisonWindow berlin = ComparisonWindow.day(date, ZoneId.of("Europe/Berlin"));
        ComparisonWindow cairo = ComparisonWindow.day(date, ZoneId.of("Africa/Cairo"));
        var crossZone = SnapshotComparisonEngine.comparePeerProgress(
                observed(summary(berlin, berlin.end(), attempts(5)), EVALUATION),
                observed(summary(cairo, cairo.end(), attempts(5)), EVALUATION), LIVE);
        assertEquals(DESCRIPTIVE_ONLY, crossZone.state());
        assertEquals(SnapshotComparisonEngine.Reason.CROSS_ZONE_OR_PERIOD, crossZone.reason());
        assertTrue(crossZone.crossZoneOrPeriod());

        ComparisonWindow forgedSameLabel = new ComparisonWindow(WindowKind.DAY, berlin.localStartDate(),
                berlin.zoneId(), null, berlin.start().plusSeconds(3600), berlin.end().plusSeconds(3600));
        var utcMismatch = SnapshotComparisonEngine.comparePeerProgress(
                observed(summary(berlin, berlin.end(), attempts(5)), EVALUATION),
                observed(summary(forgedSameLabel, forgedSameLabel.end(), attempts(5)), EVALUATION), LIVE);
        assertEquals(INCOMPATIBLE, utcMismatch.state());
        assertEquals(SnapshotComparisonEngine.Reason.UTC_INTERVAL_MISMATCH, utcMismatch.reason());

        ComparisonWindow monday = ComparisonWindow.week(LocalDate.of(2026, 9, 23),
                ZoneId.of("Europe/Berlin"), DayOfWeek.MONDAY);
        var matchedWeekContext = new SnapshotComparisonEngine.EvaluationContext(
                EVALUATION, Duration.ofDays(2), Map.of(), Map.of(),
                Map.of("review.attempts", SnapshotComparisonEngine.CohortEvidence.MATCHED));
        var sameWeek = SnapshotComparisonEngine.comparePeerProgress(
                observed(summary(monday, monday.end(), attempts(7)), EVALUATION),
                observed(summary(monday, monday.end(), attempts(5)), EVALUATION),
                matchedWeekContext);
        assertEquals(COMPARABLE, sameWeek.state());
        assertTrue(sameWeek.equalElapsed());
        assertEquals(COMPARABLE, sameWeek.metrics().get(0).state());

        ComparisonWindow sunday = ComparisonWindow.week(LocalDate.of(2026, 9, 23),
                ZoneId.of("Europe/Berlin"), DayOfWeek.SUNDAY);
        var weekPolicy = SnapshotComparisonEngine.compareHistoricalSelfProgress(
                observed(summary(monday, monday.end(), attempts(5)), EVALUATION),
                observed(summary(sunday, sunday.end(), attempts(5)), EVALUATION), LIVE);
        assertEquals(DESCRIPTIVE_ONLY, weekPolicy.state());
        assertEquals(SnapshotComparisonEngine.Reason.WEEK_POLICY_MISMATCH, weekPolicy.reason());
    }

    @Test
    void missingNotSharedStaleLowSampleUnknownVersionAndProvenanceStayDistinct() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 27), ZoneId.of("Europe/Berlin"));
        ProgressSummary measured = summary(day, day.end(), attempts(2));

        var missing = SnapshotComparisonEngine.compareHistoricalSelfProgress(
                observed(measured, EVALUATION),
                SnapshotComparisonEngine.SnapshotObservation.missing("No previous snapshot exists"), LIVE);
        assertEquals(UNAVAILABLE, missing.state());
        assertEquals(SnapshotComparisonEngine.Reason.RIGHT_MISSING, missing.reason());

        var notShared = SnapshotComparisonEngine.comparePeerProgress(
                observed(measured, EVALUATION),
                SnapshotComparisonEngine.SnapshotObservation.notShared("Peer explicitly withheld this period"), LIVE);
        assertEquals(SnapshotComparisonEngine.Reason.RIGHT_NOT_SHARED, notShared.reason());

        ComparisonWindow oldDay = ComparisonWindow.day(LocalDate.of(2026, 9, 24), ZoneId.of("Europe/Berlin"));
        ProgressSummary oldSummary = summary(oldDay, oldDay.end(), attempts(2));
        var stale = SnapshotComparisonEngine.comparePeerProgress(
                observed(measured, EVALUATION),
                observed(oldSummary, EVALUATION), LIVE);
        assertEquals(SnapshotComparisonEngine.Reason.RIGHT_STALE, stale.reason());
        assertTrue(stale.rightSnapshotAge().orElseThrow().compareTo(Duration.ofDays(2)) > 0);

        ProgressSummary insufficient = summary(day, day.end(),
                new MetricValue("review.verified_correct_rate", 1, MetricUnit.BASIS_POINTS,
                        MetricAvailability.INSUFFICIENT_DATA, 0, 5,
                        MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.EXACT_UTC));
        var lowSample = SnapshotComparisonEngine.comparePeerProgress(
                observed(insufficient, EVALUATION), observed(insufficient, EVALUATION), LIVE);
        assertEquals(SnapshotComparisonEngine.Reason.LEFT_INSUFFICIENT_SAMPLE,
                lowSample.metrics().get(0).reason());

        ProgressSummary unknown = summary(day, day.end(),
                new MetricValue("future.metric", 9, MetricUnit.COUNT, MetricAvailability.MEASURED,
                        1, 1, MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        var unknownResult = SnapshotComparisonEngine.comparePeerProgress(
                observed(unknown, EVALUATION), observed(unknown, EVALUATION), LIVE);
        assertEquals(INCOMPATIBLE, unknownResult.metrics().get(0).state());
        assertEquals(SnapshotComparisonEngine.Reason.UNKNOWN_METRIC, unknownResult.metrics().get(0).reason());

        ProgressSummary v1 = summary(day, day.end(), attempts(1));
        ProgressSummary v2 = summary(day, day.end(),
                new MetricValue("review.attempts", 2, MetricUnit.COUNT, MetricAvailability.MEASURED,
                        1, 1, MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        var versionMismatch = SnapshotComparisonEngine.comparePeerProgress(
                observed(v1, EVALUATION), observed(v2, EVALUATION), LIVE);
        assertEquals(SnapshotComparisonEngine.Reason.VERSION_MISMATCH,
                versionMismatch.metrics().get(0).reason());

        ProgressSummary selfRated = summary(day, day.end(),
                metric("review.self_rated_success_rate", 1, MetricUnit.BASIS_POINTS, 7500, 12,
                        MetricProvenance.SELF_RATED, TimestampBasis.EXACT_UTC));
        ProgressSummary legacy = summary(day, day.end(),
                metric("review.self_rated_success_rate", 1, MetricUnit.BASIS_POINTS, 7500, 12,
                        MetricProvenance.LEGACY_SELF_RATING_FALLBACK, TimestampBasis.EXACT_UTC));
        var provenance = SnapshotComparisonEngine.comparePeerProgress(
                observed(selfRated, EVALUATION), observed(legacy, EVALUATION), LIVE);
        assertEquals(DESCRIPTIVE_ONLY, provenance.metrics().get(0).state());
        assertEquals(SnapshotComparisonEngine.Reason.PROVENANCE_MISMATCH,
                provenance.metrics().get(0).reason());
    }

    @Test
    void metricOmissionAndCohortEvidenceAreExplicit() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 27), ZoneId.of("Europe/Berlin"));
        MetricValue accepted = metric("problem.accepted", 1, MetricUnit.COUNT, 3, 3,
                MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.EXACT_UTC);
        ProgressSummary left = summary(day, day.end(), accepted, attempts(5));
        ProgressSummary rightWithoutAccepted = summary(day, day.end(), attempts(4));

        var omissionContext = new SnapshotComparisonEngine.EvaluationContext(
                EVALUATION, Duration.ofDays(2),
                Map.of(),
                Map.of("problem.accepted",
                        SnapshotComparisonEngine.MetricOmission.notShared("Peer did not grant this metric")),
                Map.of("review.attempts", SnapshotComparisonEngine.CohortEvidence.MATCHED));
        var omitted = SnapshotComparisonEngine.comparePeerProgress(
                observed(left, EVALUATION), observed(rightWithoutAccepted, EVALUATION), omissionContext);
        var acceptedOmission = omitted.metrics().stream()
                .filter(metric -> metric.metricId().equals("problem.accepted"))
                .findFirst().orElseThrow();
        assertEquals(UNAVAILABLE, acceptedOmission.state());
        assertEquals(SnapshotComparisonEngine.Reason.RIGHT_METRIC_NOT_SHARED, acceptedOmission.reason());

        ProgressSummary acceptedLeft = summary(day, day.end(), accepted);
        ProgressSummary acceptedRight = summary(day, day.end(),
                metric("problem.accepted", 1, MetricUnit.COUNT, 2, 2,
                        MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.EXACT_UTC));

        var unknownCohort = SnapshotComparisonEngine.comparePeerProgress(
                observed(acceptedLeft, EVALUATION), observed(acceptedRight, EVALUATION), LIVE);
        assertEquals(DESCRIPTIVE_ONLY, unknownCohort.metrics().get(0).state());
        assertEquals(SnapshotComparisonEngine.Reason.COHORT_EVIDENCE_MISSING,
                unknownCohort.metrics().get(0).reason());

        var matchedContext = new SnapshotComparisonEngine.EvaluationContext(
                EVALUATION, Duration.ofDays(2), Map.of(), Map.of(),
                Map.of("problem.accepted", SnapshotComparisonEngine.CohortEvidence.MATCHED));
        var matched = SnapshotComparisonEngine.comparePeerProgress(
                observed(acceptedLeft, EVALUATION), observed(acceptedRight, EVALUATION), matchedContext);
        assertEquals(COMPARABLE, matched.metrics().get(0).state());
        assertEquals(SnapshotComparisonEngine.Reason.NONE, matched.metrics().get(0).reason());

        var unmatchedContext = new SnapshotComparisonEngine.EvaluationContext(
                EVALUATION, Duration.ofDays(2), Map.of(), Map.of(),
                Map.of("problem.accepted", SnapshotComparisonEngine.CohortEvidence.UNMATCHED));
        var unmatched = SnapshotComparisonEngine.comparePeerProgress(
                observed(acceptedLeft, EVALUATION), observed(acceptedRight, EVALUATION), unmatchedContext);
        assertEquals(DESCRIPTIVE_ONLY, unmatched.metrics().get(0).state());
        assertEquals(SnapshotComparisonEngine.Reason.COHORT_MISMATCH, unmatched.metrics().get(0).reason());
    }

    @Test
    void zeroBaselineNeverCreatesInfinityAndLargeCountsDoNotOverflow() {
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 27), ZoneId.of("Europe/Berlin"));
        ProgressSummary max = summary(day, day.end(),
                metric("review.attempts", 1, MetricUnit.COUNT, Long.MAX_VALUE, 0,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        ProgressSummary one = summary(day, day.end(),
                metric("review.attempts", 1, MetricUnit.COUNT, 1, 0,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        var large = SnapshotComparisonEngine.comparePeerProgress(
                observed(max, EVALUATION), observed(one, EVALUATION), LIVE);
        assertEquals(new BigDecimal("9223372036854775806"),
                large.metrics().get(0).delta().orElseThrow().absoluteChange());

        ProgressSummary zero = summary(day, day.end(),
                metric("review.attempts", 1, MetricUnit.COUNT, 0, 0,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC));
        var zeroBaseline = SnapshotComparisonEngine.comparePeerProgress(
                observed(one, EVALUATION), observed(zero, EVALUATION), LIVE);
        var delta = zeroBaseline.metrics().get(0).delta().orElseThrow();
        assertTrue(delta.relativePercentChange().isEmpty());
        assertEquals(SnapshotComparisonEngine.Reason.ZERO_BASELINE,
                delta.relativeChangeUnavailableReason().orElseThrow());
    }

    @Test
    void preparationKeepsRawScoreReadinessDefinitionGatesAndHistoricalLimitsSeparate() {
        byte[] fingerprint = new byte[32];
        fingerprint[0] = 7;
        PreparationSnapshot ready75 = preparation(fingerprint, 1, 75, 80, 80,
                DomainStatus.PASS, PreparationStatus.READY);
        PreparationSnapshot notReady90 = preparation(fingerprint, 1, 90, 80, 80,
                DomainStatus.PASS, PreparationStatus.NOT_READY);

        var thresholds = SnapshotComparisonEngine.comparePreparation(
                observed(ready75, EVALUATION), observed(notReady90, EVALUATION), LIVE);
        assertEquals(COMPARABLE, thresholds.state());
        assertTrue(thresholds.scoresComparable());
        assertFalse(thresholds.readinessComparable());
        assertEquals(SnapshotComparisonEngine.Reason.READINESS_THRESHOLD_MISMATCH, thresholds.reason());
        assertTrue(thresholds.domains().get(0).left().orElseThrow().criticalGate());
        assertTrue(thresholds.leftBlockingCriticalDomainIds().isEmpty());
        assertTrue(thresholds.rightBlockingCriticalDomainIds().isEmpty());

        byte[] changedFingerprint = fingerprint.clone();
        changedFingerprint[1] = 1;
        PreparationSnapshot changedDefinition = preparation(changedFingerprint, 1, 75, 60, 60,
                DomainStatus.FAIL, PreparationStatus.NOT_READY);
        var definitionMismatch = SnapshotComparisonEngine.comparePreparation(
                observed(ready75, EVALUATION), observed(changedDefinition, EVALUATION), LIVE);
        assertEquals(DESCRIPTIVE_ONLY, definitionMismatch.state());
        assertFalse(definitionMismatch.scoresComparable());
        assertTrue(definitionMismatch.overallPercentagePointChange().isEmpty());
        assertEquals(DESCRIPTIVE_ONLY, definitionMismatch.domains().get(0).state());
        assertEquals(List.of("core"), changedDefinition.blockingCriticalDomainIds());
        assertEquals(List.of("core"), definitionMismatch.rightBlockingCriticalDomainIds());

        PreparationSnapshot changedScoring = preparation(fingerprint, 2, 75, 80, 80,
                DomainStatus.PASS, PreparationStatus.READY);
        var scoringMismatch = SnapshotComparisonEngine.comparePreparation(
                observed(ready75, EVALUATION), observed(changedScoring, EVALUATION), LIVE);
        assertEquals(SnapshotComparisonEngine.Reason.PREPARATION_SCORING_MISMATCH, scoringMismatch.reason());

        var stage = SnapshotComparisonEngine.unavailablePreparationStage(
                observed(ready75, EVALUATION), observed(notReady90, EVALUATION));
        assertEquals(UNAVAILABLE, stage.state());
        assertEquals(SnapshotComparisonEngine.Reason.HISTORICAL_EVIDENCE_MISSING, stage.reason());
    }

    private static SnapshotComparisonEngine.SnapshotObservation<ProgressSummary> observed(
            ProgressSummary summary, Instant capturedAt) {
        return SnapshotComparisonEngine.SnapshotObservation.available(summary, capturedAt);
    }

    private static SnapshotComparisonEngine.SnapshotObservation<PreparationSnapshot> observed(
            PreparationSnapshot snapshot, Instant capturedAt) {
        return SnapshotComparisonEngine.SnapshotObservation.available(snapshot, capturedAt);
    }

    private static ProgressSummary summary(ComparisonWindow window, Instant cutoff, MetricValue... values) {
        return new ProgressSummary(window, cutoff, List.of(values).stream()
                .sorted(java.util.Comparator.comparing(MetricValue::metricId)
                        .thenComparingInt(MetricValue::metricVersion))
                .toList());
    }

    private static MetricValue attempts(long value) {
        return metric("review.attempts", 1, MetricUnit.COUNT, value, value,
                MetricProvenance.LOCAL_RECORD, TimestampBasis.EXACT_UTC);
    }

    private static MetricValue metric(String id, int version, MetricUnit unit, long value, long samples,
                                      MetricProvenance provenance, TimestampBasis timestampBasis) {
        return new MetricValue(id, version, unit, MetricAvailability.MEASURED, value, samples,
                provenance, timestampBasis);
    }

    private static PreparationSnapshot preparation(byte[] fingerprint, int scoringVersion, int overallThreshold,
                                                   int overall, int domainScore,
                                                   DomainStatus domainStatus, PreparationStatus status) {
        DomainSnapshot domain = new DomainSnapshot("core", 100, true, 70, domainScore,
                100, 1, 1, domainStatus);
        return new PreparationSnapshot("profile", fingerprint, scoringVersion, overallThreshold,
                EVALUATION.minus(Duration.ofHours(1)), overall, 100, status, List.of(domain));
    }

    private static void assertBigDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
