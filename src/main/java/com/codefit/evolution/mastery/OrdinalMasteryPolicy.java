package com.codefit.evolution.mastery;

import static com.codefit.evolution.evaluation.EvaluationTypes.CheckStatus;
import static com.codefit.evolution.evidence.EvidenceTypes.*;
import static com.codefit.evolution.mastery.MasteryTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.EstimateInput.*;
import com.codefit.evolution.mastery.MasteryEstimate.*;

import java.time.*;
import java.util.*;

/** Pure approved ordinal V1. No clock, database, Java execution, rewards or UI dependencies. */
public final class OrdinalMasteryPolicy {
    public static final String VERSION = "ordinal-v1";
    public static final Duration CURRENT_FOR = Duration.ofDays(30);
    public static final Duration RETENTION_DELAY = Duration.ofDays(7);

    private record Proof(Observation source, Set<Integer> behaviors, boolean retention) {}

    public MasteryEstimate estimate(EstimateInput input) {
        var contributions = new ArrayList<Contribution>();
        var exclusions = new ArrayList<MasteryEstimate.Exclusion>();
        var reasons = new ArrayList<Reason>();
        var provenance = new EnumMap<Provenance, Integer>(Provenance.class);
        var assistance = new EnumMap<Assistance, Integer>(Assistance.class);
        var exposure = new EnumMap<Exposure, Integer>(Exposure.class);
        var required = new TreeSet<Integer>();
        for (int i = 0; i < input.skill().observableBehaviors().size(); i++) required.add(i);
        var ordered =
                input.observations().stream()
                        .sorted(
                                Comparator.comparing(
                                                (Observation o) -> o.facts().time().observedAt(),
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(Observation::id)
                                        .thenComparing(o -> o.mapping().mappingId())
                                        .thenComparingInt(o -> o.mapping().version()))
                        .toList();
        var roots = new HashSet<String>();
        var commands = new HashSet<String>();
        var aliases = new HashSet<Long>();
        var proofs = new ArrayList<Proof>();
        var supported = new TreeSet<Integer>();
        var failedBehaviors = new TreeSet<Integer>();
        Instant last = null, contradictionAt = null;
        String contradictionId = null;
        Band historical = Band.UNKNOWN;
        Band restoringBand = Band.UNKNOWN;
        int consecutiveFailures = 0;
        boolean capped = false;
        for (var o : ordered) {
            var f = o.facts();
            Instant at = f.time().observedAt();
            ReasonCode rejected = null;
            if (at == null) rejected = ReasonCode.UNKNOWN_TIME;
            else if (at.isAfter(input.asOf())) rejected = ReasonCode.FUTURE_EVENT;
            else if (o.firstSequence() <= input.epoch().evidenceThrough()
                    || at.isBefore(input.epoch().startedAt())
                    || (!input.epoch().id().equals("initial")
                            && at.equals(input.epoch().startedAt())))
                rejected = ReasonCode.BEFORE_EPOCH;
            else if (!o.exclusions().isEmpty() || f.availability() != Availability.AVAILABLE)
                rejected =
                        o.exclusions().contains("MAPPING_SUPERSEDED")
                                ? ReasonCode.MAPPING_UNAVAILABLE
                                : o.exclusions().contains("SUPERSEDED")
                                        ? ReasonCode.SUPERSEDED
                                        : ReasonCode.UNAVAILABLE_SOURCE;
            if (rejected != null) {
                exclude(exclusions, o, rejected);
                continue;
            }
            var e = f.evaluation();
            if (!roots.add(EvidenceIds.root(f.source()))
                    || (e != null
                            && (!commands.add(e.submissionId())
                                    || (e.problemAttemptId() != null
                                            && !aliases.add(e.problemAttemptId()))))) {
                exclude(exclusions, o, ReasonCode.CORRELATED);
                continue;
            }
            provenance.merge(f.provenance(), 1, Integer::sum);
            assistance.merge(f.assistance(), 1, Integer::sum);
            exposure.merge(f.exposure(), 1, Integer::sum);
            if (f.provenance() != Provenance.LOCAL_CHECK) {
                exclude(exclusions, o, ReasonCode.UNCERTAIN_PROVENANCE);
                continue;
            }
            if (f.outcome() == Outcome.UNAVAILABLE) {
                exclude(exclusions, o, ReasonCode.UNAVAILABLE_EVALUATION);
                continue;
            }
            var c = o.context();
            if (c == null
                    || e == null
                    || c.presentedAt().isAfter(at)
                    || !e.familyId().equals(c.family())
                    || !new HashSet<>(e.checkIds())
                            .equals(
                                    c.checks().stream()
                                            .map(ch -> ch.id())
                                            .collect(java.util.stream.Collectors.toSet()))
                    || c.checks().stream().map(ch -> ch.id()).distinct().count()
                            != c.checks().size()) {
                exclude(exclusions, o, ReasonCode.INVALID_EVALUATION);
                continue;
            }
            var allRelevant =
                    c.checks().stream()
                            .filter(
                                    ch ->
                                            ch.kind() != CheckKind.REFLECTION
                                                    && ch.targets().stream()
                                                            .anyMatch(
                                                                    t ->
                                                                            t.skill()
                                                                                    .equals(
                                                                                            input.skill()
                                                                                                    .ref())))
                            .toList();
            var relevant = allRelevant.stream().filter(ch -> ch.required()).toList();
            var passing = new TreeSet<Integer>();
            var failing = new TreeSet<Integer>();
            for (int behavior : required) {
                var checks =
                        relevant.stream()
                                .filter(
                                        ch ->
                                                ch.targets().stream()
                                                        .anyMatch(
                                                                t ->
                                                                        t.skill()
                                                                                        .equals(
                                                                                                input.skill()
                                                                                                        .ref())
                                                                                && t.behavior()
                                                                                        == behavior))
                                .toList();
                if (!checks.isEmpty()
                        && checks.stream().allMatch(ch -> ch.status() == CheckStatus.PASSED))
                    passing.add(behavior);
                if (allRelevant.stream()
                        .anyMatch(
                                ch ->
                                        ch.status() == CheckStatus.FAILED
                                                && ch.targets().stream()
                                                        .anyMatch(
                                                                t ->
                                                                        t.skill()
                                                                                        .equals(
                                                                                                input.skill()
                                                                                                        .ref())
                                                                                && t.behavior()
                                                                                        == behavior)))
                    failing.add(behavior);
            }
            boolean direct = !passing.isEmpty() || !failing.isEmpty();
            if (!direct) {
                exclude(exclusions, o, ReasonCode.UNAVAILABLE_EVALUATION);
                continue;
            }
            last = at;
            historical = max(historical, Band.DEVELOPING);
            boolean unaided = f.assistance() == Assistance.NONE_OBSERVED;
            boolean knownExposure =
                    f.exposure() != Exposure.UNKNOWN && f.exposure() != Exposure.FAMILIAR_RECALL;
            boolean engineering =
                    relevant.stream().anyMatch(ch -> ch.kind() != CheckKind.KEYED_DIAGNOSTIC);
            boolean novel =
                    c.novel()
                            && Set.of(
                                            Exposure.FIRST_PRESENTATION,
                                            Exposure.HELD_OUT_TRANSFER,
                                            Exposure.DELAYED_RETENTION)
                                    .contains(f.exposure());
            boolean positive =
                    f.outcome() == Outcome.PASS
                            && !passing.isEmpty()
                            && unaided
                            && knownExposure
                            && novel
                            && engineering
                            && Set.of(
                                            Exposure.FIRST_PRESENTATION,
                                            Exposure.HELD_OUT_TRANSFER,
                                            Exposure.DELAYED_RETENTION)
                                    .contains(f.exposure())
                            && c.checks().stream()
                                    .noneMatch(ch -> ch.status() == CheckStatus.FAILED)
                            && c.checks().stream()
                                    .filter(ch -> ch.required())
                                    .allMatch(ch -> ch.status() == CheckStatus.PASSED);
            // Every family supplies at most one independent positive opportunity, even after a
            // remap.
            if (positive
                    && proofs.stream()
                            .anyMatch(p -> p.source().context().family().equals(c.family())))
                positive = false;
            contributions.add(
                    new Contribution(
                            o.id(),
                            o.mapping(),
                            e.submissionId(),
                            c.family(),
                            passing,
                            failing,
                            allRelevant.stream().map(ch -> ch.id()).toList(),
                            !failing.isEmpty() && unaided && knownExposure,
                            positive));
            if (positive) {
                boolean retention =
                        c.cohort() == Cohort.RETENTION
                                && c.heldOutEquivalent()
                                && retentionEligible(input, o, required);
                proofs.add(new Proof(o, Set.copyOf(passing), retention));
                supported.addAll(passing);
                consecutiveFailures = 0;
                reason(reasons, ReasonCode.INDEPENDENT_OPPORTUNITY, o.id());
                if (c.cohort() == Cohort.RETENTION && !retention)
                    reason(reasons, ReasonCode.RETENTION_NOT_DELAYED, o.id());
            } else if (!unaided) {
                reason(
                        reasons,
                        f.assistance() == Assistance.UNKNOWN
                                ? ReasonCode.UNKNOWN_ASSISTANCE
                                : ReasonCode.ASSISTED,
                        o.id());
            } else if (!engineering) reason(reasons, ReasonCode.DIAGNOSTIC_SCOPE, o.id());
            else if (!knownExposure)
                reason(
                        reasons,
                        f.exposure() == Exposure.UNKNOWN
                                ? ReasonCode.UNKNOWN_EXPOSURE
                                : ReasonCode.FAMILIAR,
                        o.id());
            else if (!novel)
                reason(
                        reasons,
                        f.exposure() == Exposure.FIRST_PRESENTATION
                                ? ReasonCode.UNKNOWN_EXPOSURE
                                : ReasonCode.FAMILIAR,
                        o.id());
            else reason(reasons, ReasonCode.OBSERVED_PROGRESS, o.id());
            if (!failing.isEmpty() && unaided && knownExposure) {
                consecutiveFailures++;
                var contradicted = new HashSet<>(failing);
                contradicted.retainAll(supported);
                if (!contradicted.isEmpty()) {
                    failedBehaviors.addAll(contradicted);
                    restoringBand = max(restoringBand, historical);
                    contradictionAt = at;
                    contradictionId = o.id();
                }
                if (consecutiveFailures >= 2 && contradictionAt != null) capped = true;
            }
            historical = max(historical, band(proofs, required));
        }
        Band band = band(proofs, required);
        if (band == Band.UNKNOWN && !contributions.isEmpty()) band = Band.DEVELOPING;
        boolean contradicted = contradictionAt != null;
        if (contradicted) {
            final Instant failureAt = contradictionAt;
            final String failureId = contradictionId;
            var after =
                    proofs.stream()
                            .filter(p -> compare(p.source(), failureAt, failureId) > 0)
                            .toList();
            var restored = new TreeSet<Integer>();
            after.forEach(p -> restored.addAll(p.behaviors()));
            boolean restoration = after.size() >= 2 && restored.containsAll(failedBehaviors);
            // Each affected behavior needs two fresh families, not two disjoint partial passes.
            for (int b : failedBehaviors)
                restoration &= after.stream().filter(p -> p.behaviors().contains(b)).count() >= 2;
            var freshTransfer =
                    after.stream()
                            .filter(
                                    p ->
                                            p.source().context().cohort() == Cohort.TRANSFER
                                                    && p.source().context().heldOutEquivalent()
                                                    && p.behaviors().containsAll(required))
                            .toList();
            if (restoringBand.ordinal() >= Band.TRANSFER_SUPPORTED.ordinal())
                restoration &= !freshTransfer.isEmpty();
            if (restoringBand == Band.RETENTION_SUPPORTED)
                restoration &=
                        after.stream()
                                .anyMatch(
                                        p ->
                                                p.retention()
                                                        && p.behaviors().containsAll(required)
                                                        && freshTransfer.stream()
                                                                .anyMatch(
                                                                        t ->
                                                                                !p.source()
                                                                                        .context()
                                                                                        .presentedAt()
                                                                                        .isBefore(
                                                                                                t.source()
                                                                                                        .facts()
                                                                                                        .time()
                                                                                                        .observedAt())));
            if (restoration) {
                contradicted = false;
                capped = false;
            } else {
                reason(reasons, ReasonCode.CONTRADICTED, contradictionId);
                reason(reasons, ReasonCode.RESTORATION_PENDING, contradictionId);
                if (capped) {
                    band = Band.DEVELOPING;
                    reason(reasons, ReasonCode.REPEATED_FAILURE, contradictionId);
                }
            }
        }
        Freshness freshness =
                last == null
                        ? Freshness.NO_OBSERVATION
                        : input.asOf().isAfter(last.plus(CURRENT_FOR))
                                ? Freshness.STALE
                                : Freshness.CURRENT;
        boolean mappingUncertain =
                !input.unavailableMappings().isEmpty()
                        || exclusions.stream()
                                .anyMatch(e -> e.code() == ReasonCode.MAPPING_UNAVAILABLE);
        Support support =
                band == Band.UNKNOWN || contradicted || mappingUncertain
                        ? Support.INSUFFICIENT
                        : band.ordinal() >= Band.TRANSFER_SUPPORTED.ordinal()
                                ? Support.DIVERSE
                                : Support.LIMITED;
        if (mappingUncertain) reason(reasons, ReasonCode.MAPPING_UNAVAILABLE, null);
        if (contributions.isEmpty()) reason(reasons, ReasonCode.NO_DIRECT_EVIDENCE, null);
        if (!supported.containsAll(required)) reason(reasons, ReasonCode.MISSING_BEHAVIORS, null);
        if (band == Band.DEVELOPING) reason(reasons, ReasonCode.NEED_DIVERSITY, null);
        if (band.ordinal() >= Band.INDEPENDENT.ordinal())
            reason(reasons, ReasonCode.INDEPENDENT_FAMILIES, null);
        if (band.ordinal() >= Band.TRANSFER_SUPPORTED.ordinal())
            reason(reasons, ReasonCode.TRANSFER, null);
        if (band == Band.RETENTION_SUPPORTED) reason(reasons, ReasonCode.RETENTION, null);
        if (freshness == Freshness.STALE) reason(reasons, ReasonCode.STALE, null);
        boolean independentCurrent =
                proofs.stream()
                        .anyMatch(
                                p ->
                                        !input.asOf()
                                                .isAfter(
                                                        p.source()
                                                                .facts()
                                                                .time()
                                                                .observedAt()
                                                                .plus(CURRENT_FOR)));
        if (band.ordinal() >= Band.INDEPENDENT.ordinal() && !independentCurrent)
            reason(reasons, ReasonCode.INDEPENDENT_VALIDATION_DUE, null);
        reason(reasons, ReasonCode.CURRENT_SCOPE, null);
        return new MasteryEstimate(
                input.skill().ref(),
                input.graph(),
                input.epoch(),
                band,
                max(historical, band),
                support,
                freshness,
                contradicted,
                capped,
                band.ordinal() >= Band.INDEPENDENT.ordinal()
                        && !contradicted
                        && !mappingUncertain
                        && freshness == Freshness.CURRENT
                        && independentCurrent,
                proofs.size(),
                supported,
                provenance,
                assistance,
                exposure,
                last,
                input.asOf(),
                VERSION,
                contributions,
                exclusions,
                reasons);
    }

    private static int compare(Observation o, Instant at, String id) {
        int c = o.facts().time().observedAt().compareTo(at);
        return c == 0 ? o.id().compareTo(id) : c;
    }

    private static Band band(List<Proof> proofs, Set<Integer> required) {
        // Held-out observations cannot simultaneously supply the two prerequisite practice units.
        var foundation =
                proofs.stream()
                        .filter(
                                p ->
                                        p.source().context().cohort() == Cohort.PRACTICE
                                                || p.source().context().cohort() == Cohort.BASELINE)
                        .toList();
        var covered = new HashSet<Integer>();
        foundation.forEach(p -> covered.addAll(p.behaviors()));
        if (foundation.size() < 2
                || foundation.stream()
                        .noneMatch(p -> p.source().context().cohort() == Cohort.PRACTICE)
                || !covered.containsAll(required))
            return proofs.isEmpty() ? Band.UNKNOWN : Band.DEVELOPING;
        var transfer =
                proofs.stream()
                        .filter(
                                p ->
                                        p.source().context().cohort() == Cohort.TRANSFER
                                                && p.source().context().heldOutEquivalent()
                                                && p.behaviors().containsAll(required))
                        .toList();
        if (transfer.isEmpty()) return Band.INDEPENDENT;
        // Validation must follow foundation; an early exposed transfer cannot later be relabelled
        // unseen.
        var eligibleTransfer =
                transfer.stream()
                        .filter(
                                p -> {
                                    var prior =
                                            foundation.stream()
                                                    .filter(
                                                            f ->
                                                                    !f.source()
                                                                            .facts()
                                                                            .time()
                                                                            .observedAt()
                                                                            .isAfter(
                                                                                    p.source()
                                                                                            .context()
                                                                                            .presentedAt()))
                                                    .toList();
                                    var priorCoverage = new HashSet<Integer>();
                                    prior.forEach(f -> priorCoverage.addAll(f.behaviors()));
                                    return prior.size() >= 2
                                            && prior.stream()
                                                    .anyMatch(
                                                            f ->
                                                                    f.source().context().cohort()
                                                                            == Cohort.PRACTICE)
                                            && priorCoverage.containsAll(required);
                                })
                        .toList();
        if (eligibleTransfer.isEmpty()) return Band.INDEPENDENT;
        Instant transferAt =
                eligibleTransfer.stream()
                        .map(p -> p.source().facts().time().observedAt())
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return proofs.stream()
                        .anyMatch(
                                p ->
                                        p.retention()
                                                && p.behaviors().containsAll(required)
                                                && !p.source()
                                                        .context()
                                                        .presentedAt()
                                                        .isBefore(transferAt))
                ? Band.RETENTION_SUPPORTED
                : Band.TRANSFER_SUPPORTED;
    }

    private static boolean retentionEligible(
            EstimateInput input, Observation o, Set<Integer> required) {
        Instant start = o.context().presentedAt();
        // Exclude only this presentation. Any other same-behavior exposure/attempt is rehearsal,
        // regardless of whether it succeeded, was later withdrawn, or belongs to an older epoch.
        if (input.activities().stream()
                .anyMatch(a -> a.at() == null && !Collections.disjoint(a.behaviors(), required)))
            return false;
        var relevant =
                input.activities().stream()
                        .filter(
                                a ->
                                        !a.id().equals(
                                                                "presentation:"
                                                                        + o.context()
                                                                                .presentationId())
                                                && !a.id().equals(
                                                                "submission:"
                                                                        + o.facts()
                                                                                .evaluation()
                                                                                .submissionId())
                                                && !a.at().isAfter(o.facts().time().observedAt())
                                                && !Collections.disjoint(a.behaviors(), required))
                        .toList();
        if (relevant.stream().anyMatch(a -> a.at().isAfter(start))) return false;
        var latest =
                relevant.stream()
                        .filter(a -> !a.at().isAfter(start))
                        .map(Activity::at)
                        .max(Comparator.naturalOrder());
        return latest.isPresent() && !start.isBefore(latest.get().plus(RETENTION_DELAY));
    }

    private static Band max(Band a, Band b) {
        return a.ordinal() > b.ordinal() ? a : b;
    }

    private static void exclude(
            List<MasteryEstimate.Exclusion> out, Observation o, ReasonCode code) {
        out.add(new MasteryEstimate.Exclusion(o.id(), o.mapping(), code, message(code)));
    }

    private static void reason(List<Reason> out, ReasonCode code, String id) {
        out.add(new Reason(code, message(code), id == null ? List.of() : List.of(id)));
    }

    public static String message(ReasonCode code) {
        return switch (code) {
            case NO_DIRECT_EVIDENCE ->
                    "There is not enough direct evidence to estimate this skill.";
            case UNAVAILABLE_SOURCE ->
                    "A result was withdrawn, changed or is unavailable; it no longer supports this"
                            + " estimate.";
            case SUPERSEDED -> "A later correction replaces this observation.";
            case MAPPING_UNAVAILABLE ->
                    "An exact mapping is unavailable or superseded; older positive mappings are not"
                            + " substituted.";
            case UNKNOWN_TIME ->
                    "The event time is unknown; ingestion time cannot establish recency.";
            case FUTURE_EVENT -> "The observation occurs after the requested as-of time.";
            case BEFORE_EPOCH -> "This observation predates the learner's estimate reset.";
            case CORRELATED ->
                    "This is another interpretation or alias of the same deliberate opportunity.";
            case UNCERTAIN_PROVENANCE ->
                    "Self-report, legacy grading and item recall do not establish independent"
                            + " engineering ability.";
            case UNAVAILABLE_EVALUATION ->
                    "The evaluator did not verify relevant performance; no negative skill"
                            + " conclusion was made.";
            case INVALID_EVALUATION ->
                    "Exact durable evaluation context is unavailable or inconsistent.";
            case ASSISTED ->
                    "The result was assisted, so it supports practice progress rather than"
                            + " independent proficiency.";
            case UNKNOWN_ASSISTANCE ->
                    "Assistance conditions are unknown; independence is not established.";
            case FAMILIAR ->
                    "The family was familiar or repeated; this does not add independent diversity"
                            + " or unseen transfer.";
            case UNKNOWN_EXPOSURE -> "Prior exposure is unknown; novelty is not established.";
            case DIAGNOSTIC_SCOPE ->
                    "A keyed diagnostic supports the checked diagnosis, not independent code"
                            + " repair.";
            case MISSING_BEHAVIORS ->
                    "Independent evidence does not yet cover every required skill behavior.";
            case OBSERVED_PROGRESS ->
                    "Relevant checks show observable progress or difficulty within their bounded"
                            + " scope.";
            case INDEPENDENT_OPPORTUNITY ->
                    "One independent exercise supports the tested behaviors.";
            case NEED_DIVERSITY ->
                    "Two unrelated independent exercises, including practice outside baseline, are"
                            + " needed with full behavior coverage.";
            case INDEPENDENT_FAMILIES ->
                    "At least two independent challenge families support the required behaviors.";
            case TRANSFER ->
                    "A separate reserved, unfamiliar context supports transfer within the checked"
                            + " skill scope.";
            case RETENTION ->
                    "A delayed independent check supports retention after at least seven days"
                            + " without recorded relevant rehearsal.";
            case RETENTION_NOT_DELAYED ->
                    "The delayed check lacks a known seven-day interval without relevant"
                            + " rehearsal.";
            case CONTRADICTED ->
                    "An independent failure contradicts previously supported behavior; current"
                            + " proficiency is uncertain.";
            case REPEATED_FAILURE ->
                    "Two consecutive deliberate failures cap current status at DEVELOPING; earlier"
                            + " successes remain in history.";
            case RESTORATION_PENDING ->
                    "Two new independent families on affected behavior are needed; transfer and"
                            + " retention also require fresh validation.";
            case STALE -> "Your evidence is older than 30 days; a fresh validation is recommended.";
            case INDEPENDENT_VALIDATION_DUE ->
                    "Recent assisted or familiar practice does not refresh independent validation;"
                            + " a fresh unfamiliar challenge is needed.";
            case CURRENT_SCOPE ->
                    "This estimate concerns the named checked behaviors, not universal correctness"
                            + " or outside-help verification.";
        };
    }
}
