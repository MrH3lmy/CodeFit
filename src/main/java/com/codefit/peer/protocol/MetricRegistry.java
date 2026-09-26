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
                    "subjective reviews plus legacy objective rows without validation_result; GOOD/EASY count as success"),
            new MetricDefinition("problem.attempts", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LOCAL_RECORD),
                    "problem_attempts rows with submitted_at in window"),
            new MetricDefinition("problem.accepted", 1, MetricUnit.COUNT, 0,
                    Set.of(MetricProvenance.LEARNER_REPORTED_OUTCOME),
                    "problem_attempts rows recorded as ACCEPTED by the learner in window"),
            new MetricDefinition("problem.solving_seconds", 1, MetricUnit.SECONDS, 0,
                    Set.of(MetricProvenance.LOCAL_TIMER),
                    "sum of phase-timer seconds on problem_attempts in window"),
            new MetricDefinition("mock.overall_score", 1, MetricUnit.PERCENT, 1,
                    Set.of(MetricProvenance.MOCK_SELF_SCORE),
                    "mean interview_mock_runs.overall_score_percent completed in window"));

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
