package com.codefit.peer.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Metric definitions known to this build (v1 catalog). A value whose {@code (id, version)} is unknown
 * is still structurally valid - newer peers may send newer metrics - but it is incomparable and must be
 * shown as "not comparable", never coerced.
 *
 * <p>Verified correctness only ever uses {@link MetricProvenance#VERIFIED_LOCAL_VALIDATION}; the
 * legacy self-rating fallback and subjective ratings have their own metric so they can never inflate a
 * "verified" figure.
 */
public final class MetricRegistry {

    public static final List<MetricDefinition> V1 = List.of(
            new MetricDefinition("review.attempts", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LOCAL_RECORD),
                    "review_history rows (boss_battle = 0) with reviewed_at in window"),
            new MetricDefinition("review.verified_correct_rate", 1, MetricUnit.BASIS_POINTS, 10,
                    Set.of(MetricProvenance.VERIFIED_LOCAL_VALIDATION),
                    "objective review_history rows with a non-blank validation_result; excludes the legacy self-rating fallback"),
            new MetricDefinition("review.self_rated_success_rate", 1, MetricUnit.BASIS_POINTS, 10,
                    Set.of(MetricProvenance.SELF_RATED, MetricProvenance.LEGACY_SELF_RATING_FALLBACK),
                    "GOOD/EASY counts as success; a single instance's samples are always genuinely one "
                            + "provenance or the other, never pooled (#183 review fix: the legacy fallback evidence "
                            + "also has its own dedicated metric, review.legacy_rating_fallback_success_rate, so a "
                            + "caller choosing to keep the two separate per window never needs to use this id for "
                            + "legacy-fallback samples at all - this definition stays permissive at the registry "
                            + "level only for the rare case upstream evidence genuinely is self-rated by either path)"),
            new MetricDefinition("problem.attempts", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LOCAL_RECORD),
                    "#183 review fix (round 6): period-attributable problem_attempts rows only "
                            + "(FRESH_ATTEMPT/PREVIOUSLY_SOLVED). IMPORTED rows are excluded; if the window contains "
                            + "pre-column UNKNOWN rows, the metric is UNAVAILABLE because old main already stored both "
                            + "genuine attempts and workbook imports without durable time provenance"),
            new MetricDefinition("problem.accepted", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LEARNER_REPORTED_OUTCOME),
                    "accepted period-attributable problem_attempts in window; IMPORTED is excluded and UNKNOWN "
                            + "makes the metric UNAVAILABLE (see problem.attempts)"),
            new MetricDefinition("problem.solving_seconds", 1, MetricUnit.SECONDS, 0,
                    Set.of(MetricProvenance.LOCAL_TIMER),
                    "sum of phase-timer seconds on period-attributable problem_attempts in window; IMPORTED is "
                            + "excluded and UNKNOWN makes the metric UNAVAILABLE (see problem.attempts)"),
            new MetricDefinition("mock.overall_score", 1, MetricUnit.PERCENT, 1,
                    Set.of(MetricProvenance.MOCK_SELF_SCORE),
                    "mean interview_mock_runs.overall_score_percent completed in window"),
            new MetricDefinition("problem.unique_completed", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LEARNER_REPORTED_OUTCOME),
                    "#183 review fix (round 4): problems whose FIRST successful (AC/ACX) problem_attempts row "
                            + "both falls in window and carries completion_origin = FRESH_ATTEMPT - never "
                            + "problem_progress.completed_at, which later successful re-attempts overwrite, and "
                            + "never a markPreviouslySolved() attempt (completion_origin = PREVIOUSLY_SOLVED), an "
                            + "imported row (completion_origin = IMPORTED), or a row whose origin predates this "
                            + "column (completion_origin = UNKNOWN) - those carry no trustworthy evidence of when the "
                            + "problem was actually first completed, however early their own submitted_at is "
                            + "(see ProgressSnapshotService#uniqueCompletedProblems, "
                            + "ProblemAttemptRepository#countFreshFirstCompletionsBetweenUtc)"),
            new MetricDefinition("review.hint_free_rate", 1, MetricUnit.BASIS_POINTS, 10,
                    Set.of(MetricProvenance.LOCAL_RECORD),
                    "#183: share of review_history rows (boss_battle = 0) with hint_used = 0, restricted to "
                            + "rows where hint_usage_recorded = 1 - a legacy row defaulted to hint_used = 0 before "
                            + "hint tracking existed is never counted as a known hint-free sample"),
            new MetricDefinition("review.legacy_rating_fallback_success_rate", 1, MetricUnit.BASIS_POINTS, 10,
                    Set.of(MetricProvenance.LEGACY_SELF_RATING_FALLBACK),
                    "#183 review fix: legacy non-subjective review_history rows with no validation_result, graded "
                            + "by rating (GOOD/EASY = success); kept separate from review.self_rated_success_rate so "
                            + "neither metric's declared provenance describes samples it doesn't own"));

    private static final Map<String, MetricDefinition> BY_KEY = V1.stream()
            .collect(Collectors.toUnmodifiableMap(d -> key(d.metricId(), d.version()), Function.identity()));

    private MetricRegistry() {
    }

    public static Optional<MetricDefinition> find(String metricId, int version) {
        return Optional.ofNullable(BY_KEY.get(key(metricId, version)));
    }

    private static String key(String metricId, int version) {
        return metricId + "@" + version;
    }
}
