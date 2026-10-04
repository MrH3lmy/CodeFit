package com.codefit.peer.comparison;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.DomainSnapshot;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricDefinition;
import com.codefit.peer.protocol.MetricRegistry;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.WindowKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Pure, deterministic comparison logic over already decoded, verified and permitted peer snapshots.
 *
 * <p>This type performs no I/O and makes no trust or sharing decision. Callers must supply observation
 * metadata from the accepted envelope/local capture context and must enforce authorization before
 * invoking the engine.
 */
public final class SnapshotComparisonEngine {

    private static final int RELATIVE_CHANGE_SCALE = 4;

    private SnapshotComparisonEngine() {
    }

    public enum ComparisonState {
        COMPARABLE,
        DESCRIPTIVE_ONLY,
        UNAVAILABLE,
        INCOMPATIBLE
    }

    public enum InputState {
        AVAILABLE,
        MISSING,
        NOT_SHARED,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        LEFT_MISSING,
        RIGHT_MISSING,
        LEFT_NOT_SHARED,
        RIGHT_NOT_SHARED,
        LEFT_UNAVAILABLE,
        RIGHT_UNAVAILABLE,
        LEFT_STALE,
        RIGHT_STALE,
        LEFT_CAPTURED_IN_FUTURE,
        RIGHT_CAPTURED_IN_FUTURE,
        LEFT_SNAPSHOT_IN_FUTURE,
        RIGHT_SNAPSHOT_IN_FUTURE,
        WINDOW_KIND_MISMATCH,
        CROSS_ZONE_OR_PERIOD,
        UTC_INTERVAL_MISMATCH,
        WEEK_POLICY_MISMATCH,
        CUTOFF_MISMATCH,
        COMPLETE_PARTIAL_MISMATCH,
        LEFT_METRIC_MISSING,
        RIGHT_METRIC_MISSING,
        LEFT_METRIC_NOT_SHARED,
        RIGHT_METRIC_NOT_SHARED,
        UNKNOWN_METRIC,
        VERSION_MISMATCH,
        UNIT_MISMATCH,
        PROVENANCE_MISMATCH,
        COHORT_EVIDENCE_MISSING,
        COHORT_MISMATCH,
        LEFT_METRIC_UNAVAILABLE,
        RIGHT_METRIC_UNAVAILABLE,
        LEFT_INSUFFICIENT_SAMPLE,
        RIGHT_INSUFFICIENT_SAMPLE,
        ZERO_BASELINE,
        PREPARATION_PROFILE_MISMATCH,
        PREPARATION_SCORING_MISMATCH,
        READINESS_THRESHOLD_MISMATCH,
        SCORE_MISSING,
        DOMAIN_DEFINITION_MISMATCH,
        HISTORICAL_EVIDENCE_MISSING
    }

    public enum DeltaKind {
        COUNT,
        SECONDS,
        PERCENTAGE_POINTS
    }

    /** Whether the caller has evidence that both sides used a matched task/difficulty cohort. */
    public enum CohortEvidence {
        MATCHED,
        UNMATCHED,
        UNKNOWN
    }

    /** Explicit context for a metric that is absent from one supplied summary. */
    public record MetricOmission(InputState state, String detail) {
        public MetricOmission {
            Objects.requireNonNull(state, "state");
            if (state == InputState.AVAILABLE) {
                throw new IllegalArgumentException("A metric omission cannot be AVAILABLE.");
            }
            detail = detail == null ? "" : detail;
        }

        public static MetricOmission missing(String detail) {
            return new MetricOmission(InputState.MISSING, detail);
        }

        public static MetricOmission notShared(String detail) {
            return new MetricOmission(InputState.NOT_SHARED, detail);
        }

        public static MetricOmission unavailable(String detail) {
            return new MetricOmission(InputState.UNAVAILABLE, detail);
        }
    }

    /**
     * Explicit evaluation context. The caller chooses a freshness horizon appropriate to the use case;
     * historical self-comparisons can deliberately use a longer horizon than live peer comparisons.
     *
     * <p>Metric omission maps are only consulted when that metric id is actually absent on the given
     * side. Cohort evidence defaults to UNKNOWN, which permits a descriptive delta but not a claim of
     * matched performance.
     */
    public record EvaluationContext(Instant evaluationInstant, Duration maxSnapshotAge,
                                    Map<String, MetricOmission> leftMetricOmissions,
                                    Map<String, MetricOmission> rightMetricOmissions,
                                    Map<String, CohortEvidence> cohortEvidenceByMetric) {
        public EvaluationContext {
            Objects.requireNonNull(evaluationInstant, "evaluationInstant");
            Objects.requireNonNull(maxSnapshotAge, "maxSnapshotAge");
            if (maxSnapshotAge.isNegative()) {
                throw new IllegalArgumentException("maxSnapshotAge must not be negative.");
            }
            leftMetricOmissions = Map.copyOf(Objects.requireNonNull(leftMetricOmissions, "leftMetricOmissions"));
            rightMetricOmissions = Map.copyOf(Objects.requireNonNull(rightMetricOmissions, "rightMetricOmissions"));
            cohortEvidenceByMetric = Map.copyOf(
                    Objects.requireNonNull(cohortEvidenceByMetric, "cohortEvidenceByMetric"));
        }

        public EvaluationContext(Instant evaluationInstant, Duration maxSnapshotAge) {
            this(evaluationInstant, maxSnapshotAge, Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * One supplied snapshot plus observation/capture metadata. An omitted value is never silently
     * interpreted as private: {@link InputState#NOT_SHARED} must be explicitly supplied by the caller.
     */
    public record SnapshotObservation<T>(T snapshot, Instant capturedAt, InputState state, String detail) {
        public SnapshotObservation {
            Objects.requireNonNull(state, "state");
            detail = detail == null ? "" : detail;
            if (state == InputState.AVAILABLE) {
                Objects.requireNonNull(snapshot, "snapshot");
                Objects.requireNonNull(capturedAt, "capturedAt");
                Instant snapshotInstant = snapshotInstant(snapshot, capturedAt);
                if (capturedAt.isBefore(snapshotInstant)) {
                    throw new IllegalArgumentException(
                            "Capture metadata cannot predate the snapshot cutoff/capturedAt.");
                }
            } else if (snapshot != null || capturedAt != null) {
                throw new IllegalArgumentException("Unavailable observations cannot carry a snapshot or capturedAt.");
            }
        }

        public static <T> SnapshotObservation<T> available(T snapshot, Instant capturedAt) {
            return new SnapshotObservation<>(snapshot, capturedAt, InputState.AVAILABLE, "");
        }

        public static <T> SnapshotObservation<T> missing(String detail) {
            return new SnapshotObservation<>(null, null, InputState.MISSING, detail);
        }

        public static <T> SnapshotObservation<T> notShared(String detail) {
            return new SnapshotObservation<>(null, null, InputState.NOT_SHARED, detail);
        }

        public static <T> SnapshotObservation<T> unavailable(String detail) {
            return new SnapshotObservation<>(null, null, InputState.UNAVAILABLE, detail);
        }

        public Optional<T> snapshotOptional() {
            return Optional.ofNullable(snapshot);
        }

        public Optional<Instant> capturedAtOptional() {
            return Optional.ofNullable(capturedAt);
        }
    }

    /**
     * Delta from right/baseline to left/current. Relative change is absent for a zero baseline rather
     * than becoming NaN/infinity.
     */
    public record MetricDelta(DeltaKind kind, BigDecimal absoluteChange,
                              Optional<BigDecimal> relativePercentChange,
                              Optional<Reason> relativeChangeUnavailableReason) {
        public MetricDelta {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(absoluteChange, "absoluteChange");
            relativePercentChange = relativePercentChange == null ? Optional.empty() : relativePercentChange;
            relativeChangeUnavailableReason = relativeChangeUnavailableReason == null
                    ? Optional.empty() : relativeChangeUnavailableReason;
            if (relativePercentChange.isPresent() == relativeChangeUnavailableReason.isPresent()) {
                throw new IllegalArgumentException(
                        "Exactly one of relativePercentChange or relativeChangeUnavailableReason must be present.");
            }
        }
    }

    /** Values retain sample size, provenance and timestamp-basis uncertainty from the wire snapshot. */
    public record MetricComparison(String metricId, ComparisonState state, Reason reason,
                                   Optional<MetricValue> left, Optional<MetricValue> right,
                                   Optional<MetricDelta> delta) {
        public MetricComparison {
            Objects.requireNonNull(metricId, "metricId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            left = left == null ? Optional.empty() : left;
            right = right == null ? Optional.empty() : right;
            delta = delta == null ? Optional.empty() : delta;
        }
    }

    /**
     * Progress result. For peer comparisons, {@code crossZoneOrPeriod} makes non-identical labels
     * explicit. For historical-self comparisons the windows are expected to have different dates.
     */
    public record ProgressComparison(ComparisonState state, Reason reason,
                                     SnapshotObservation<ProgressSummary> left,
                                     SnapshotObservation<ProgressSummary> right,
                                     Optional<Duration> leftSnapshotAge,
                                     Optional<Duration> rightSnapshotAge,
                                     boolean crossZoneOrPeriod, boolean equalElapsed,
                                     List<MetricComparison> metrics) {
        public ProgressComparison {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            leftSnapshotAge = leftSnapshotAge == null ? Optional.empty() : leftSnapshotAge;
            rightSnapshotAge = rightSnapshotAge == null ? Optional.empty() : rightSnapshotAge;
            metrics = List.copyOf(metrics);
        }
    }

    public record DomainComparison(String domainId, ComparisonState state, Reason reason,
                                   Optional<DomainSnapshot> left, Optional<DomainSnapshot> right,
                                   Optional<BigDecimal> scorePercentagePointChange,
                                   Optional<BigDecimal> coveragePercentagePointChange) {
        public DomainComparison {
            Objects.requireNonNull(domainId, "domainId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            left = left == null ? Optional.empty() : left;
            right = right == null ? Optional.empty() : right;
            scorePercentagePointChange = scorePercentagePointChange == null
                    ? Optional.empty() : scorePercentagePointChange;
            coveragePercentagePointChange = coveragePercentagePointChange == null
                    ? Optional.empty() : coveragePercentagePointChange;
        }
    }

    /**
     * Preparation result keeps raw-score compatibility separate from READY/NOT_READY compatibility.
     * Blocking critical gates remain available through the retained snapshots.
     */
    public record PreparationComparison(ComparisonState state, Reason reason,
                                        SnapshotObservation<PreparationSnapshot> left,
                                        SnapshotObservation<PreparationSnapshot> right,
                                        Optional<Duration> leftSnapshotAge,
                                        Optional<Duration> rightSnapshotAge,
                                        boolean scoresComparable, boolean readinessComparable,
                                        Optional<BigDecimal> overallPercentagePointChange,
                                        Optional<BigDecimal> coveragePercentagePointChange,
                                        List<String> leftBlockingCriticalDomainIds,
                                        List<String> rightBlockingCriticalDomainIds,
                                        List<DomainComparison> domains) {
        public PreparationComparison {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            leftSnapshotAge = leftSnapshotAge == null ? Optional.empty() : leftSnapshotAge;
            rightSnapshotAge = rightSnapshotAge == null ? Optional.empty() : rightSnapshotAge;
            overallPercentagePointChange = overallPercentagePointChange == null
                    ? Optional.empty() : overallPercentagePointChange;
            coveragePercentagePointChange = coveragePercentagePointChange == null
                    ? Optional.empty() : coveragePercentagePointChange;
            leftBlockingCriticalDomainIds = List.copyOf(leftBlockingCriticalDomainIds);
            rightBlockingCriticalDomainIds = List.copyOf(rightBlockingCriticalDomainIds);
            domains = List.copyOf(domains);
        }
    }

    /** Compare two peers for an intended same day/week. */
    public static ProgressComparison comparePeerProgress(SnapshotObservation<ProgressSummary> left,
                                                         SnapshotObservation<ProgressSummary> right,
                                                         EvaluationContext context) {
        return compareProgress(left, right, context, false);
    }

    /** Compare a current day/week with a real supplied previous day/week snapshot. */
    public static ProgressComparison compareHistoricalSelfProgress(SnapshotObservation<ProgressSummary> current,
                                                                   SnapshotObservation<ProgressSummary> previous,
                                                                   EvaluationContext context) {
        return compareProgress(current, previous, context, true);
    }

    private static ProgressComparison compareProgress(SnapshotObservation<ProgressSummary> left,
                                                      SnapshotObservation<ProgressSummary> right,
                                                      EvaluationContext context,
                                                      boolean historicalSelf) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(context, "context");

        Gate leftGate = gate(left, context, true);
        if (!leftGate.allowed()) {
            return new ProgressComparison(ComparisonState.UNAVAILABLE, leftGate.reason(), left, right,
                    snapshotAge(left, context), snapshotAge(right, context), false, false, List.of());
        }
        Gate rightGate = gate(right, context, false);
        if (!rightGate.allowed()) {
            return new ProgressComparison(ComparisonState.UNAVAILABLE, rightGate.reason(), left, right,
                    snapshotAge(left, context), snapshotAge(right, context), false, false, List.of());
        }

        ProgressSummary a = left.snapshot();
        ProgressSummary b = right.snapshot();
        ComparisonWindow wa = a.window();
        ComparisonWindow wb = b.window();

        if (wa.kind() != wb.kind()) {
            return new ProgressComparison(ComparisonState.INCOMPATIBLE, Reason.WINDOW_KIND_MISMATCH,
                    left, right, snapshotAge(left, context), snapshotAge(right, context),
                    true, false, List.of());
        }

        boolean crossZoneOrPeriod;
        Reason topReason = Reason.NONE;
        ComparisonState topState = ComparisonState.COMPARABLE;

        if (historicalSelf) {
            boolean sameZone = wa.zoneId().equals(wb.zoneId());
            boolean sameWeekPolicy = wa.kind() != WindowKind.WEEK || Objects.equals(wa.weekStart(), wb.weekStart());
            crossZoneOrPeriod = !sameZone || !sameWeekPolicy;
            if (!sameWeekPolicy) {
                topState = ComparisonState.DESCRIPTIVE_ONLY;
                topReason = Reason.WEEK_POLICY_MISMATCH;
            } else if (!sameZone) {
                topState = ComparisonState.DESCRIPTIVE_ONLY;
                topReason = Reason.CROSS_ZONE_OR_PERIOD;
            }
        } else {
            boolean sameLabel = wa.sameLocalPeriodAs(wb);
            boolean sameUtcInterval = wa.start().equals(wb.start()) && wa.end().equals(wb.end());
            if (sameLabel && !sameUtcInterval) {
                return new ProgressComparison(ComparisonState.INCOMPATIBLE, Reason.UTC_INTERVAL_MISMATCH,
                        left, right, snapshotAge(left, context), snapshotAge(right, context),
                        false, false, List.of());
            }
            crossZoneOrPeriod = !sameLabel;
            if (crossZoneOrPeriod) {
                topState = ComparisonState.DESCRIPTIVE_ONLY;
                topReason = Reason.CROSS_ZONE_OR_PERIOD;
            }
        }

        Alignment alignment = alignment(a, b);
        if (!alignment.aligned()) {
            return new ProgressComparison(ComparisonState.UNAVAILABLE, alignment.reason(),
                    left, right, snapshotAge(left, context), snapshotAge(right, context),
                    crossZoneOrPeriod, false, List.of());
        }

        List<MetricComparison> metrics = compareMetrics(a.metrics(), b.metrics(), topState, context);
        return new ProgressComparison(topState, topReason, left, right,
                snapshotAge(left, context), snapshotAge(right, context),
                crossZoneOrPeriod, true, metrics);
    }

    public static PreparationComparison comparePreparation(SnapshotObservation<PreparationSnapshot> left,
                                                           SnapshotObservation<PreparationSnapshot> right,
                                                           EvaluationContext context) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(context, "context");

        Gate leftGate = gate(left, context, true);
        if (!leftGate.allowed()) {
            return unavailablePreparation(leftGate.reason(), left, right, context);
        }
        Gate rightGate = gate(right, context, false);
        if (!rightGate.allowed()) {
            return unavailablePreparation(rightGate.reason(), left, right, context);
        }

        PreparationSnapshot a = left.snapshot();
        PreparationSnapshot b = right.snapshot();

        boolean scoresComparable = a.scoresComparableWith(b);
        boolean readinessComparable = a.readinessComparableWith(b);

        if (!scoresComparable) {
            Reason reason;
            if (!a.profileId().equals(b.profileId())
                    || !java.util.Arrays.equals(a.profileFingerprint(), b.profileFingerprint())) {
                reason = Reason.PREPARATION_PROFILE_MISMATCH;
            } else {
                reason = Reason.PREPARATION_SCORING_MISMATCH;
            }
            return new PreparationComparison(ComparisonState.DESCRIPTIVE_ONLY, reason, left, right,
                    snapshotAge(left, context), snapshotAge(right, context),
                    false, false, Optional.empty(), Optional.empty(),
                    a.blockingCriticalDomainIds(), b.blockingCriticalDomainIds(),
                    compareDomains(a.domains(), b.domains(), false));
        }

        Optional<BigDecimal> overallDelta = a.overallPercent() == null || b.overallPercent() == null
                ? Optional.empty()
                : Optional.of(BigDecimal.valueOf((long) a.overallPercent() - b.overallPercent()));
        Optional<BigDecimal> coverageDelta = Optional.of(
                BigDecimal.valueOf((long) a.coveragePercent() - b.coveragePercent()));

        Reason reason = readinessComparable ? Reason.NONE : Reason.READINESS_THRESHOLD_MISMATCH;
        return new PreparationComparison(ComparisonState.COMPARABLE, reason, left, right,
                snapshotAge(left, context), snapshotAge(right, context),
                true, readinessComparable, overallDelta, coverageDelta,
                a.blockingCriticalDomainIds(), b.blockingCriticalDomainIds(),
                compareDomains(a.domains(), b.domains(), true));
    }

    /**
     * Stage/elapsed-preparation comparisons require dated start/checkpoint evidence that is not part
     * of the v1 preparation snapshot. This helper makes that limitation explicit instead of inventing
     * a baseline.
     */
    public static PreparationComparison unavailablePreparationStage(
            SnapshotObservation<PreparationSnapshot> left,
            SnapshotObservation<PreparationSnapshot> right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return new PreparationComparison(ComparisonState.UNAVAILABLE, Reason.HISTORICAL_EVIDENCE_MISSING,
                left, right, Optional.empty(), Optional.empty(),
                false, false, Optional.empty(), Optional.empty(), List.of(), List.of(), List.of());
    }

    private static PreparationComparison unavailablePreparation(
            Reason reason,
            SnapshotObservation<PreparationSnapshot> left,
            SnapshotObservation<PreparationSnapshot> right,
            EvaluationContext context) {
        return new PreparationComparison(ComparisonState.UNAVAILABLE, reason, left, right,
                snapshotAge(left, context), snapshotAge(right, context),
                false, false, Optional.empty(), Optional.empty(), List.of(), List.of(), List.of());
    }

    private static <T> Optional<Duration> snapshotAge(SnapshotObservation<T> observation,
                                                       EvaluationContext context) {
        if (observation.state() != InputState.AVAILABLE) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(
                snapshotInstant(observation.snapshot(), observation.capturedAt()),
                context.evaluationInstant()));
    }

    private static <T> Gate gate(SnapshotObservation<T> observation,
                                 EvaluationContext context,
                                 boolean left) {
        if (observation.state() != InputState.AVAILABLE) {
            Reason reason = switch (observation.state()) {
                case MISSING -> left ? Reason.LEFT_MISSING : Reason.RIGHT_MISSING;
                case NOT_SHARED -> left ? Reason.LEFT_NOT_SHARED : Reason.RIGHT_NOT_SHARED;
                case UNAVAILABLE -> left ? Reason.LEFT_UNAVAILABLE : Reason.RIGHT_UNAVAILABLE;
                case AVAILABLE -> throw new IllegalStateException("unreachable");
            };
            return new Gate(false, reason);
        }

        if (observation.capturedAt().isAfter(context.evaluationInstant())) {
            return new Gate(false, left ? Reason.LEFT_CAPTURED_IN_FUTURE : Reason.RIGHT_CAPTURED_IN_FUTURE);
        }
        Instant snapshotInstant = snapshotInstant(observation.snapshot(), observation.capturedAt());
        if (snapshotInstant.isAfter(context.evaluationInstant())) {
            return new Gate(false, left ? Reason.LEFT_SNAPSHOT_IN_FUTURE : Reason.RIGHT_SNAPSHOT_IN_FUTURE);
        }
        Duration age = Duration.between(snapshotInstant, context.evaluationInstant());
        if (age.compareTo(context.maxSnapshotAge()) > 0) {
            return new Gate(false, left ? Reason.LEFT_STALE : Reason.RIGHT_STALE);
        }
        return new Gate(true, Reason.NONE);
    }

    private static Instant snapshotInstant(Object snapshot, Instant fallbackCapturedAt) {
        if (snapshot instanceof ProgressSummary summary) {
            return summary.cutoff();
        }
        if (snapshot instanceof PreparationSnapshot preparation) {
            return preparation.capturedAt();
        }
        return fallbackCapturedAt;
    }

    /**
     * A Study Match ({@code WindowKind.MATCH}) window is never independently computed by each side
     * the way a {@code DAY}/{@code WEEK} window is from a local date and zone - both participants
     * already converged on the identical {@code startedAt}/{@code endsAt} instants before either one
     * ever captures a snapshot against it (see {@code ComparisonWindow#match}'s own javadoc), and
     * {@link #compareProgress} has already rejected a UTC-interval mismatch before this method is
     * even reached. The DAY/WEEK "equal elapsed time" requirement below exists specifically to make
     * two <em>independently anchored</em> partial periods comparable; a match's two partial captures
     * are already anchored to the exact same instant, so requiring their cutoffs to also be
     * byte-identical would reject "my progress just now" against "your progress from your last
     * refresh" merely because they were captured a few milliseconds (or minutes) apart - exactly the
     * false "incompatible windows" PR B's own daily comparison already tolerates by design, but which
     * this feature's own brief explicitly asks not to reproduce for an active match. A match is
     * therefore always aligned once the window-kind/UTC-interval checks above already passed.
     */
    private static Alignment alignment(ProgressSummary left, ProgressSummary right) {
        if (left.window().kind() == WindowKind.MATCH) {
            return new Alignment(true, Reason.NONE);
        }
        boolean leftComplete = left.periodComplete();
        boolean rightComplete = right.periodComplete();
        if (leftComplete && rightComplete) {
            return new Alignment(true, Reason.NONE);
        }
        if (leftComplete != rightComplete) {
            return new Alignment(false, Reason.COMPLETE_PARTIAL_MISMATCH);
        }
        Duration leftElapsed = Duration.between(left.window().start(), left.cutoff());
        Duration rightElapsed = Duration.between(right.window().start(), right.cutoff());
        if (!leftElapsed.equals(rightElapsed)) {
            return new Alignment(false, Reason.CUTOFF_MISMATCH);
        }
        return new Alignment(true, Reason.NONE);
    }

    private static List<MetricComparison> compareMetrics(List<MetricValue> leftValues,
                                                         List<MetricValue> rightValues,
                                                         ComparisonState topState,
                                                         EvaluationContext context) {
        Map<String, List<MetricValue>> leftById = groupMetrics(leftValues);
        Map<String, List<MetricValue>> rightById = groupMetrics(rightValues);
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(leftById.keySet());
        ids.addAll(rightById.keySet());

        List<MetricComparison> result = new ArrayList<>();
        for (String id : ids) {
            List<MetricValue> left = leftById.getOrDefault(id, List.of());
            List<MetricValue> right = rightById.getOrDefault(id, List.of());
            appendMetricComparisons(id, left, right, topState, context, result);
        }
        return List.copyOf(result);
    }

    private static Map<String, List<MetricValue>> groupMetrics(List<MetricValue> values) {
        Map<String, List<MetricValue>> byId = new LinkedHashMap<>();
        for (MetricValue value : values) {
            byId.computeIfAbsent(value.metricId(), ignored -> new ArrayList<>()).add(value);
        }
        byId.replaceAll((ignored, metrics) -> metrics.stream()
                .sorted(Comparator.comparingInt(MetricValue::metricVersion)).toList());
        return byId;
    }

    private static void appendMetricComparisons(String id,
                                                List<MetricValue> left,
                                                List<MetricValue> right,
                                                ComparisonState topState,
                                                EvaluationContext context,
                                                List<MetricComparison> output) {
        Map<Integer, MetricValue> leftByVersion = new LinkedHashMap<>();
        Map<Integer, MetricValue> rightByVersion = new LinkedHashMap<>();
        left.forEach(v -> leftByVersion.put(v.metricVersion(), v));
        right.forEach(v -> rightByVersion.put(v.metricVersion(), v));

        TreeSet<Integer> commonVersions = new TreeSet<>(leftByVersion.keySet());
        commonVersions.retainAll(rightByVersion.keySet());
        for (Integer version : commonVersions) {
            output.add(compareMetricPair(id, leftByVersion.remove(version), rightByVersion.remove(version),
                    topState, context));
        }

        boolean leftMissingCompletely = left.isEmpty();
        boolean rightMissingCompletely = right.isEmpty();
        List<MetricValue> leftOnly = new ArrayList<>(leftByVersion.values());
        List<MetricValue> rightOnly = new ArrayList<>(rightByVersion.values());
        int paired = Math.min(leftOnly.size(), rightOnly.size());
        for (int i = 0; i < paired; i++) {
            output.add(new MetricComparison(id, ComparisonState.INCOMPATIBLE, Reason.VERSION_MISMATCH,
                    Optional.of(leftOnly.get(i)), Optional.of(rightOnly.get(i)), Optional.empty()));
        }
        for (int i = paired; i < leftOnly.size(); i++) {
            Reason reason = rightMissingCompletely
                    ? missingMetricReason(false, id, context) : Reason.RIGHT_METRIC_MISSING;
            output.add(new MetricComparison(id, ComparisonState.UNAVAILABLE, reason,
                    Optional.of(leftOnly.get(i)), Optional.empty(), Optional.empty()));
        }
        for (int i = paired; i < rightOnly.size(); i++) {
            Reason reason = leftMissingCompletely
                    ? missingMetricReason(true, id, context) : Reason.LEFT_METRIC_MISSING;
            output.add(new MetricComparison(id, ComparisonState.UNAVAILABLE, reason,
                    Optional.empty(), Optional.of(rightOnly.get(i)), Optional.empty()));
        }
    }

    private static MetricComparison compareMetricPair(String id,
                                                      MetricValue left,
                                                      MetricValue right,
                                                      ComparisonState topState,
                                                      EvaluationContext context) {
        Optional<MetricDefinition> definition = MetricRegistry.find(id, left.metricVersion());
        if (definition.isEmpty()) {
            return metric(id, ComparisonState.INCOMPATIBLE, Reason.UNKNOWN_METRIC, left, right);
        }
        if (left.unit() != right.unit() || left.unit() != definition.get().unit()) {
            return metric(id, ComparisonState.INCOMPATIBLE, Reason.UNIT_MISMATCH, left, right);
        }
        if (left.provenance() != right.provenance()) {
            return metric(id, ComparisonState.DESCRIPTIVE_ONLY, Reason.PROVENANCE_MISMATCH, left, right);
        }
        if (left.availability() == MetricAvailability.UNAVAILABLE) {
            return metric(id, ComparisonState.UNAVAILABLE, Reason.LEFT_METRIC_UNAVAILABLE, left, right);
        }
        if (right.availability() == MetricAvailability.UNAVAILABLE) {
            return metric(id, ComparisonState.UNAVAILABLE, Reason.RIGHT_METRIC_UNAVAILABLE, left, right);
        }
        if (left.availability() == MetricAvailability.INSUFFICIENT_DATA) {
            return metric(id, ComparisonState.UNAVAILABLE, Reason.LEFT_INSUFFICIENT_SAMPLE, left, right);
        }
        if (right.availability() == MetricAvailability.INSUFFICIENT_DATA) {
            return metric(id, ComparisonState.UNAVAILABLE, Reason.RIGHT_INSUFFICIENT_SAMPLE, left, right);
        }

        MetricDelta delta = delta(left.unit(), left.value(), right.value());
        ComparisonState state = topState == ComparisonState.DESCRIPTIVE_ONLY
                ? ComparisonState.DESCRIPTIVE_ONLY : ComparisonState.COMPARABLE;
        Reason reason = Reason.NONE;
        CohortEvidence cohortEvidence = context.cohortEvidenceByMetric()
                .getOrDefault(id, CohortEvidence.UNKNOWN);
        if (cohortEvidence == CohortEvidence.UNKNOWN) {
            state = ComparisonState.DESCRIPTIVE_ONLY;
            reason = Reason.COHORT_EVIDENCE_MISSING;
        } else if (cohortEvidence == CohortEvidence.UNMATCHED) {
            state = ComparisonState.DESCRIPTIVE_ONLY;
            reason = Reason.COHORT_MISMATCH;
        }
        return new MetricComparison(id, state, reason, Optional.of(left), Optional.of(right),
                Optional.of(delta));
    }

    private static Reason missingMetricReason(boolean left, String metricId, EvaluationContext context) {
        MetricOmission omission = (left ? context.leftMetricOmissions() : context.rightMetricOmissions())
                .get(metricId);
        if (omission == null || omission.state() == InputState.MISSING) {
            return left ? Reason.LEFT_METRIC_MISSING : Reason.RIGHT_METRIC_MISSING;
        }
        if (omission.state() == InputState.NOT_SHARED) {
            return left ? Reason.LEFT_METRIC_NOT_SHARED : Reason.RIGHT_METRIC_NOT_SHARED;
        }
        return left ? Reason.LEFT_METRIC_UNAVAILABLE : Reason.RIGHT_METRIC_UNAVAILABLE;
    }

    private static MetricComparison metric(String id, ComparisonState state, Reason reason,
                                           MetricValue left, MetricValue right) {
        return new MetricComparison(id, state, reason, Optional.of(left), Optional.of(right), Optional.empty());
    }

    private static MetricDelta delta(MetricUnit unit, long left, long right) {
        BigDecimal rawDifference = BigDecimal.valueOf(left).subtract(BigDecimal.valueOf(right));
        DeltaKind kind;
        BigDecimal absolute;
        switch (unit) {
            case COUNT -> {
                kind = DeltaKind.COUNT;
                absolute = rawDifference;
            }
            case SECONDS -> {
                kind = DeltaKind.SECONDS;
                absolute = rawDifference;
            }
            case BASIS_POINTS -> {
                kind = DeltaKind.PERCENTAGE_POINTS;
                absolute = rawDifference.movePointLeft(2);
            }
            case PERCENT -> {
                kind = DeltaKind.PERCENTAGE_POINTS;
                absolute = rawDifference;
            }
            default -> throw new IllegalStateException("Unsupported unit " + unit);
        }

        if (right == 0L) {
            return new MetricDelta(kind, absolute, Optional.empty(), Optional.of(Reason.ZERO_BASELINE));
        }
        BigDecimal relative = rawDifference
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(right), RELATIVE_CHANGE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new MetricDelta(kind, absolute, Optional.of(relative), Optional.empty());
    }

    private static List<DomainComparison> compareDomains(List<DomainSnapshot> leftDomains,
                                                         List<DomainSnapshot> rightDomains,
                                                         boolean definitionsComparable) {
        Map<String, DomainSnapshot> left = leftDomains.stream()
                .collect(java.util.stream.Collectors.toMap(DomainSnapshot::domainId, d -> d));
        Map<String, DomainSnapshot> right = rightDomains.stream()
                .collect(java.util.stream.Collectors.toMap(DomainSnapshot::domainId, d -> d));
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(left.keySet());
        ids.addAll(right.keySet());

        List<DomainComparison> result = new ArrayList<>();
        for (String id : ids) {
            DomainSnapshot a = left.get(id);
            DomainSnapshot b = right.get(id);
            if (a == null) {
                result.add(new DomainComparison(id, ComparisonState.UNAVAILABLE, Reason.LEFT_METRIC_MISSING,
                        Optional.empty(), Optional.of(b), Optional.empty(), Optional.empty()));
                continue;
            }
            if (b == null) {
                result.add(new DomainComparison(id, ComparisonState.UNAVAILABLE, Reason.RIGHT_METRIC_MISSING,
                        Optional.of(a), Optional.empty(), Optional.empty(), Optional.empty()));
                continue;
            }
            if (!definitionsComparable || !domainDefinitionMatches(a, b)) {
                result.add(new DomainComparison(id, ComparisonState.DESCRIPTIVE_ONLY,
                        Reason.DOMAIN_DEFINITION_MISMATCH, Optional.of(a), Optional.of(b),
                        Optional.empty(), Optional.empty()));
                continue;
            }

            Optional<BigDecimal> scoreDelta = a.scorePercent() == null || b.scorePercent() == null
                    ? Optional.empty()
                    : Optional.of(BigDecimal.valueOf((long) a.scorePercent() - b.scorePercent()));
            Optional<BigDecimal> coverageDelta = Optional.of(
                    BigDecimal.valueOf((long) a.coveragePercent() - b.coveragePercent()));
            Reason reason = scoreDelta.isPresent() ? Reason.NONE : Reason.SCORE_MISSING;
            ComparisonState state = scoreDelta.isPresent() ? ComparisonState.COMPARABLE : ComparisonState.UNAVAILABLE;
            result.add(new DomainComparison(id, state, reason, Optional.of(a), Optional.of(b),
                    scoreDelta, coverageDelta));
        }
        return List.copyOf(result);
    }

    private static boolean domainDefinitionMatches(DomainSnapshot left, DomainSnapshot right) {
        return left.weightPercent() == right.weightPercent()
                && left.criticalGate() == right.criticalGate()
                && Objects.equals(left.thresholdPercent(), right.thresholdPercent())
                && left.totalRequirementCount() == right.totalRequirementCount();
    }

    private record Gate(boolean allowed, Reason reason) {
    }

    private record Alignment(boolean aligned, Reason reason) {
    }
}
