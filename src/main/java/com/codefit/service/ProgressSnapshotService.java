package com.codefit.service;

import com.codefit.model.ProblemAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.SubmissionResult;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.snapshot.LocalProgressSnapshot;
import com.codefit.repository.InterviewMockRepository;
import com.codefit.repository.LocalProgressSnapshotRepository;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ReviewHistoryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns real, already-stored CodeFit evidence into a {@link LocalProgressSnapshot} for one
 * {@link ComparisonWindow} — #183's "define evidence before metrics" step. Every query here is a
 * fresh, explicit UTC- or zone-bounded query against the underlying repositories; it deliberately does
 * <strong>not</strong> reuse {@code StatsService}'s or {@code ProblemDashboardService}'s own date
 * filters, because those were built for dashboard display, not for a signed, comparison-grade window
 * (per the parent epic: "Dashboard date filters do not uniformly mean 'all metrics in this time
 * window'; audit source queries before defining day/week projections").
 *
 * <h2>Which bound, UTC or zone-local</h2>
 * Per {@code com.codefit.peer.protocol.TimestampBasis} (protocol-v1 §7.3): columns written by SQLite's
 * own {@code CURRENT_TIMESTAMP} ({@code review_history.reviewed_at}, {@code problem_attempts.submitted_at})
 * are exact UTC despite carrying no offset, so they are queried with this window's UTC
 * {@code [start, cutoff)} bound. Columns written from Java {@code LocalDateTime.now()}
 * ({@code interview_mock_runs.completed_at}) are only ever approximately the comparison zone's local
 * time, so they are queried with this window's bounds converted into that zone instead. Mixing these
 * up would silently misplace evidence across a midnight/week boundary - exactly the bug this split
 * exists to prevent. ({@code problem_progress.completed_at} is deliberately not used by this class at
 * all - see {@link #uniqueCompletedProblems}.)
 *
 * <h2>Partial periods</h2>
 * Every query's upper bound is {@code cutoff}, never {@code window.end()}: a snapshot captured before
 * the window closes describes only the evidence that exists so far, which is exactly what a partial-
 * period comparison ({@code ComparisonWindow#equalElapsedCutoff}) needs.
 */
public class ProgressSnapshotService {
    /** Minimum samples for a rate metric to be {@code MEASURED} rather than {@code INSUFFICIENT_DATA}; matches {@code MetricRegistry}. */
    private static final int RATE_MIN_SAMPLES = 10;

    private final ReviewHistoryRepository reviewHistoryRepository;
    private final ProblemAttemptRepository problemAttemptRepository;
    private final InterviewMockRepository interviewMockRepository;
    private final LocalProgressSnapshotRepository snapshotRepository;
    private final Clock clock;

    public ProgressSnapshotService() {
        this(new ReviewHistoryRepository(), new ProblemAttemptRepository(),
                new InterviewMockRepository(), new LocalProgressSnapshotRepository(), Clock.systemUTC());
    }

    ProgressSnapshotService(ReviewHistoryRepository reviewHistoryRepository, ProblemAttemptRepository problemAttemptRepository,
                             InterviewMockRepository interviewMockRepository,
                             LocalProgressSnapshotRepository snapshotRepository, Clock clock) {
        this.reviewHistoryRepository = reviewHistoryRepository;
        this.problemAttemptRepository = problemAttemptRepository;
        this.interviewMockRepository = interviewMockRepository;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    /**
     * Computes and persists the snapshot for {@code window} as of right now — always this service's
     * own trusted {@link Clock} ({@link Clock#systemUTC()} in production, {@link Clock#fixed} in
     * tests), never a value a caller could choose. An earlier version of this method took an
     * {@code Instant now} parameter instead; that let a caller evaluate a still-open window's cutoff at
     * an arbitrary point, which is harmless on its own, but the same pattern elsewhere in this PR let a
     * caller backdate a genuinely new capture — removed here too for the same reason, and so every
     * capture's {@code capturedAt} is honest about when it actually ran.
     *
     * @return the captured snapshot and how it relates to any snapshot previously captured for the same window
     */
    public Capture capture(ComparisonWindow window) {
        Instant now = wireNow();
        Instant cutoff = window.cutoffAt(now);
        List<MetricValue> metrics = computeMetrics(window, cutoff);
        LocalProgressSnapshotRepository.SaveResult result = snapshotRepository.save(window, cutoff, now, metrics);
        return new Capture(result.snapshot(), result.outcome());
    }

    /**
     * Computes a comparison-only summary through an explicit historical cutoff without mutating the
     * persisted local snapshot/outbox source state. The cutoff is data time; {@code capturedAt}
     * remains the real current time from this service's trusted clock.
     *
     * <p>This exists for fair peer comparisons of an in-progress DAY/WEEK: if the peer's latest
     * accepted summary describes 14:57:31 elapsed today and I click Compare at 14:58:10, my side can
     * truthfully recompute its own evidence through the same elapsed point while recording that the
     * computation itself happened at 14:58:10. Persisting this historical view would be wrong because
     * it could replace a newer normal local capture with an older cutoff under a higher revision.
     */
    ComparisonCapture captureForComparison(ComparisonWindow window, Instant cutoff) {
        Instant capturedAt = wireNow();
        if (cutoff.isBefore(window.start()) || cutoff.isAfter(window.end())) {
            throw new IllegalArgumentException("Comparison cutoff must lie within [window.start, window.end].");
        }
        if (cutoff.isAfter(capturedAt)) {
            throw new IllegalArgumentException("Comparison cutoff cannot be in the future.");
        }

        List<MetricValue> metrics = new ArrayList<>(computeMetrics(window, cutoff));
        metrics.sort(Comparator.comparing(MetricValue::metricId).thenComparingInt(MetricValue::metricVersion));
        return new ComparisonCapture(new ProgressSummary(window, cutoff, metrics), capturedAt);
    }

    public record Capture(LocalProgressSnapshot snapshot, LocalProgressSnapshotRepository.SaveOutcome outcome) {
    }

    record ComparisonCapture(ProgressSummary summary, Instant capturedAt) {
    }

    private Instant wireNow() {
        // ProgressSummary/EnvelopeHeader require wire timestamps at millisecond precision, while
        // Clock.systemUTC() reports nanoseconds on most JVMs.
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private List<MetricValue> computeMetrics(ComparisonWindow window, Instant cutoff) {
        LocalDateTime utcStart = toUtc(window.start());
        LocalDateTime utcEnd = toUtc(cutoff);
        ZoneId zone = ZoneId.of(window.zoneId());
        LocalDateTime zoneStart = window.start().atZone(zone).toLocalDateTime();
        LocalDateTime zoneEnd = cutoff.atZone(zone).toLocalDateTime();

        List<ReviewHistory> reviews = reviewHistoryRepository.findReviewedBetweenUtc(utcStart, utcEnd);
        List<ReviewHistoryRepository.HintEvidence> hintEvidence = reviewHistoryRepository.findHintEvidenceBetweenUtc(utcStart, utcEnd);
        List<ProblemAttempt> attempts = problemAttemptRepository.findSubmittedBetweenUtc(utcStart, utcEnd);
        boolean hasAmbiguousLegacyAttempts = problemAttemptRepository.hasUnknownSubmittedBetweenUtc(utcStart, utcEnd);
        int freshFirstCompletions = problemAttemptRepository.countFreshFirstCompletionsBetweenUtc(utcStart, utcEnd);
        List<Integer> mockScores = interviewMockRepository.findOverallScoresCompletedBetween(zoneStart, zoneEnd);

        List<MetricValue> metrics = new ArrayList<>();
        metrics.add(reviewAttempts(reviews));
        metrics.add(verifiedCorrectRate(reviews));
        metrics.add(selfRatedSuccessRate(reviews));
        metrics.add(legacyRatingFallbackSuccessRate(reviews));
        metrics.add(hintFreeRate(hintEvidence));
        metrics.add(problemAttempts(attempts, hasAmbiguousLegacyAttempts));
        metrics.add(problemAccepted(attempts, hasAmbiguousLegacyAttempts));
        metrics.add(problemSolvingSeconds(attempts, hasAmbiguousLegacyAttempts));
        metrics.add(uniqueCompletedProblems(freshFirstCompletions));
        metrics.add(mockOverallScore(mockScores));
        return metrics;
    }

    // --- review_history-derived metrics (LEGACY_SQLITE_UTC: exact UTC, no offset stored) ---

    private MetricValue reviewAttempts(List<ReviewHistory> reviews) {
        return count("review.attempts", 1, reviews.size(), 0, MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    private MetricValue verifiedCorrectRate(List<ReviewHistory> reviews) {
        long denominator = 0;
        long numerator = 0;
        for (ReviewHistory review : reviews) {
            if (review.isSubjective()) {
                continue;
            }
            String validation = review.getValidationResult();
            if (validation == null || validation.isBlank()) {
                continue; // legacy self-rating fallback: never counted as verified
            }
            denominator++;
            if (review.isObjectivelyCorrect()) {
                numerator++;
            }
        }
        return rate("review.verified_correct_rate", 1, numerator, denominator,
                MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    /**
     * Genuinely subjective self-ratings only (the learner rated their own recall, no judge/validator
     * was ever involved). {@code review.legacy_rating_fallback_success_rate} is the separate metric for
     * the different evidence class below; the two are never pooled into one, because
     * {@link com.codefit.peer.protocol.MetricProvenance} has no "mixed" code for a single metric
     * instance (unlike {@link TimestampBasis#MIXED}) — a signed metric whose declared provenance does
     * not describe every one of its own samples is exactly the fabrication #183 forbids.
     */
    private MetricValue selfRatedSuccessRate(List<ReviewHistory> reviews) {
        long count = 0;
        long success = 0;
        for (ReviewHistory review : reviews) {
            if (!review.isSubjective()) {
                continue;
            }
            count++;
            if (isSuccess(review)) {
                success++;
            }
        }
        return rate("review.self_rated_success_rate", 1, success, count,
                MetricProvenance.SELF_RATED, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    /**
     * Legacy, non-subjective review_history rows that predate {@code validation_result} and so fall
     * back to grading by the stored rating (GOOD/EASY = success) — {@link ReviewHistory#isObjectivelyCorrect()}'s
     * own legacy fallback, kept in its own metric with its own honest
     * {@link MetricProvenance#LEGACY_SELF_RATING_FALLBACK} provenance rather than pooled into
     * {@code review.self_rated_success_rate}, whose declared {@link MetricProvenance#SELF_RATED} must
     * never silently describe a sample it doesn't own.
     */
    private MetricValue legacyRatingFallbackSuccessRate(List<ReviewHistory> reviews) {
        long count = 0;
        long success = 0;
        for (ReviewHistory review : reviews) {
            if (review.isSubjective()) {
                continue;
            }
            String validation = review.getValidationResult();
            if (validation != null && !validation.isBlank()) {
                continue; // objectively verified - not the legacy fallback
            }
            count++;
            if (isSuccess(review)) {
                success++;
            }
        }
        return rate("review.legacy_rating_fallback_success_rate", 1, success, count,
                MetricProvenance.LEGACY_SELF_RATING_FALLBACK, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    private static boolean isSuccess(ReviewHistory review) {
        return review.getRating() == ReviewRating.GOOD || review.getRating() == ReviewRating.EASY;
    }

    /**
     * {@code hint_used} alone cannot distinguish "the app recorded no hint was used" from "this row
     * predates hint tracking and was defaulted to 0" (#183 review fix). Only rows with
     * {@code hint_usage_recorded = 1} contribute to either the numerator or the denominator; a legacy
     * row with unknown hint evidence is excluded from both, never counted as a known hint-free sample.
     */
    private MetricValue hintFreeRate(List<ReviewHistoryRepository.HintEvidence> hintEvidence) {
        long denominator = 0;
        long numerator = 0;
        for (ReviewHistoryRepository.HintEvidence evidence : hintEvidence) {
            if (!evidence.hintUsageRecorded()) {
                continue;
            }
            denominator++;
            if (!evidence.hintUsed()) {
                numerator++;
            }
        }
        return rate("review.hint_free_rate", 1, numerator, denominator, MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    // --- problem_attempts-derived metrics (LEGACY_SQLITE_UTC) ---
    // #183 review fix (round 6): only FRESH_ATTEMPT/PREVIOUSLY_SOLVED rows have trustworthy
    // period attribution. New IMPORTED rows are excluded outright. Pre-column UNKNOWN rows are
    // ambiguous because main already persisted workbook imports before completion_origin existed,
    // so a window containing UNKNOWN cannot produce an exact attempt/accepted/timer total. Mark
    // those metrics UNAVAILABLE instead of counting an import timestamp or silently undercounting.

    private MetricValue problemAttempts(List<ProblemAttempt> attempts, boolean hasAmbiguousLegacyAttempts) {
        if (hasAmbiguousLegacyAttempts) {
            return unavailable("problem.attempts", MetricUnit.COUNT, MetricProvenance.LOCAL_RECORD,
                    TimestampBasis.LEGACY_SQLITE_UTC);
        }
        return count("problem.attempts", 1, attempts.size(), 0, MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    private MetricValue problemAccepted(List<ProblemAttempt> attempts, boolean hasAmbiguousLegacyAttempts) {
        if (hasAmbiguousLegacyAttempts) {
            return unavailable("problem.accepted", MetricUnit.COUNT, MetricProvenance.LEARNER_REPORTED_OUTCOME,
                    TimestampBasis.LEGACY_SQLITE_UTC);
        }
        long accepted = attempts.stream()
                .filter(a -> a.submissionResult() == SubmissionResult.AC || a.submissionResult() == SubmissionResult.ACX)
                .count();
        return count("problem.accepted", 1, accepted, 0, MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    private MetricValue problemSolvingSeconds(List<ProblemAttempt> attempts, boolean hasAmbiguousLegacyAttempts) {
        if (hasAmbiguousLegacyAttempts) {
            return unavailable("problem.solving_seconds", MetricUnit.SECONDS, MetricProvenance.LOCAL_TIMER,
                    TimestampBasis.LEGACY_SQLITE_UTC);
        }
        long totalSeconds = attempts.stream().mapToLong(a ->
                nz(a.readingTimeSeconds()) + nz(a.thinkingTimeSeconds()) + nz(a.codingTimeSeconds()) + nz(a.debuggingTimeSeconds())
        ).sum();
        return new MetricValue("problem.solving_seconds", 1, MetricUnit.SECONDS, MetricAvailability.MEASURED,
                totalSeconds, attempts.size(), MetricProvenance.LOCAL_TIMER, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    // --- problem_attempts-derived metric (LEGACY_SQLITE_UTC) ---

    /**
     * #183 review fix: {@code problem_progress.completed_at} is mutable - every later successful
     * re-attempt at an already-solved problem overwrites it with the real current time
     * ({@code ProblemSolvingWorkspaceService#applyProgressForOutcome}), which would move a problem's
     * completion into whichever window last happened to touch it. {@code problem_attempts} rows are
     * append-only (never updated or deleted - see {@code ProblemAttemptRepository}), so the FIRST
     * successful (AC/ACX) attempt's own {@code submitted_at} is immutable evidence of when a problem
     * was first completed, and recomputing history can never move it. A problem marked SOLVED only via
     * import or legacy data, with no matching timestamped attempt, simply never appears in this count
     * for any window - unavailable, never fabricated - rather than being derived from the mutable
     * progress row.
     *
     * <p>Round 4: "first successful attempt on file" alone is still not enough. {@code
     * ProblemSolvingWorkspaceService#markPreviouslySolved} also records a {@code SUBMITTED}/{@code
     * ACX} attempt - for a problem the learner solved before ever using CodeFit, at a time CodeFit
     * has no record of - so it can legitimately be the earliest AC/ACX row for a problem without
     * being first-completion evidence at all. {@code
     * ProblemAttemptRepository#countFreshFirstCompletionsBetweenUtc} additionally requires that
     * earliest row's {@code completion_origin} be {@code FRESH_ATTEMPT} (see {@link
     * com.codefit.model.CompletionOrigin}), which only a genuine real-time workspace finish sets -
     * including a genuine accept-after-failures ACX, which stays countable. {@code
     * markPreviouslySolved} attempts, imported rows ({@code IMPORTED}), and any row whose origin
     * predates this column ({@code UNKNOWN}), are all excluded the same way a missing attempt is:
     * unavailable, never fabricated.
     */
    private MetricValue uniqueCompletedProblems(int freshFirstCompletionsInWindow) {
        return count("problem.unique_completed", 1, freshFirstCompletionsInWindow, 0,
                MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.LEGACY_SQLITE_UTC);
    }

    // --- interview_mock_runs-derived metric (LEGACY_LOCAL_ASSUMED_ZONE) ---

    private MetricValue mockOverallScore(List<Integer> scores) {
        if (scores.isEmpty()) {
            return new MetricValue("mock.overall_score", 1, MetricUnit.PERCENT, MetricAvailability.INSUFFICIENT_DATA,
                    0, 0, MetricProvenance.MOCK_SELF_SCORE, TimestampBasis.LEGACY_LOCAL_ASSUMED_ZONE);
        }
        long sum = 0;
        for (int score : scores) {
            sum += score;
        }
        long mean = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(scores.size()), 0, RoundingMode.HALF_UP).longValueExact();
        return new MetricValue("mock.overall_score", 1, MetricUnit.PERCENT, MetricAvailability.MEASURED, mean, scores.size(),
                MetricProvenance.MOCK_SELF_SCORE, TimestampBasis.LEGACY_LOCAL_ASSUMED_ZONE);
    }

    // --- shared metric-value construction ---

    private static MetricValue unavailable(String metricId, MetricUnit unit,
                                           MetricProvenance provenance, TimestampBasis basis) {
        return new MetricValue(metricId, 1, unit, MetricAvailability.UNAVAILABLE,
                0, 0, provenance, basis);
    }

    private static MetricValue count(String metricId, int version, long value, int minSamples,
                                       MetricProvenance provenance, TimestampBasis basis) {
        MetricAvailability availability = value >= minSamples ? MetricAvailability.MEASURED : MetricAvailability.INSUFFICIENT_DATA;
        long reportedValue = availability == MetricAvailability.MEASURED ? value : 0;
        return new MetricValue(metricId, version, MetricUnit.COUNT, availability, reportedValue, value, provenance, basis);
    }

    /**
     * {@code value} is the rate in basis points, {@code round(numerator * 10000 / denominator)},
     * HALF_UP - the exact rounding a caller must invert to recover the numerator deterministically
     * (used by tests asserting exact fixture numerators).
     */
    private static MetricValue rate(String metricId, int version, long numerator, long denominator,
                                      MetricProvenance provenance, TimestampBasis basis) {
        if (denominator < RATE_MIN_SAMPLES) {
            return new MetricValue(metricId, version, MetricUnit.BASIS_POINTS, MetricAvailability.INSUFFICIENT_DATA,
                    0, denominator, provenance, basis);
        }
        long basisPoints = BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).longValueExact();
        return new MetricValue(metricId, version, MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED,
                basisPoints, denominator, provenance, basis);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static LocalDateTime toUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
}
