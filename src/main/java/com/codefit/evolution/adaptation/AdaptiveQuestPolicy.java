package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;
import static com.codefit.evolution.mastery.MasteryTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationInput.*;
import com.codefit.evolution.adaptation.NextQuestDecision.*;
import com.codefit.evolution.evidence.EvidenceTypes;

import java.time.*;
import java.util.*;

/** Ordered ordinal-v1 consumer. Never derives mastery, calls a clock, or invents content. */
public final class AdaptiveQuestPolicy {
    public static final String VERSION = "adaptive-v1";
    public static final String CONFIGURATION = "java-pilot-v1";
    private static final Duration QUIET = Duration.ofDays(7);
    private static final List<String> CONTROLS =
            List.of(
                    "PAUSE",
                    "CHANGE_GOAL",
                    "CHANGE_BUDGET",
                    "SWITCH_TRACK",
                    "DECLINE",
                    "DEFER",
                    "EXPLORE",
                    "REST");

    public NextQuestDecision decide(AdaptationInput in) {
        if (!VERSION.equals(in.policy()) || !CONFIGURATION.equals(in.configuration()))
            throw new IllegalArgumentException(
                    "Unavailable adaptation policy/configuration interpreter");
        if (in.preferences().paused())
            return stopped(
                    in,
                    Branch.PAUSED,
                    Reason.PAUSED,
                    "You paused this track; no new task has been selected.");
        if (in.preferences().track() == Track.CURRICULUM) {
            var action =
                    in.curriculumProblem() == null
                            ? null
                            : new Selection(
                                    Action.CURRICULUM,
                                    null,
                                    null,
                                    null,
                                    Set.of(),
                                    0,
                                    0,
                                    null,
                                    in.curriculumProblem(),
                                    false);
            return new NextQuestDecision(
                    Branch.CURRICULUM,
                    action,
                    List.of(),
                    List.of(),
                    List.of(
                            explain(
                                    Reason.CURRICULUM_FRONTIER,
                                    action == null
                                            ? "The existing curriculum selector returned no next"
                                                    + " problem."
                                            : "Continue at the existing imported curriculum"
                                                    + " frontier.",
                                    null)),
                    CONTROLS);
        }
        if (!in.graphAvailable() || in.skills().isEmpty())
            return stopped(
                    in,
                    Branch.UNAVAILABLE,
                    Reason.CONTEXT_UNAVAILABLE,
                    "The exact selected graph or estimate context is unavailable. Ordinary study"
                            + " remains available.");
        if (in.skills().stream().anyMatch(s -> !"ordinal-v1".equals(s.estimatePolicy())))
            return stopped(
                    in,
                    Branch.UNAVAILABLE,
                    Reason.CONTEXT_UNAVAILABLE,
                    "An exact estimate policy is unsupported; no advancement was inferred.");
        if (!validPrerequisites(in.skills()))
            return stopped(
                    in,
                    Branch.UNAVAILABLE,
                    Reason.WRONG_GRAPH,
                    "The exact prerequisite context is cyclic or incomplete; no task was"
                            + " selected.");
        var bySkill = new TreeMap<SkillRef, SkillState>();
        in.skills().forEach(s -> bySkill.put(s.skill(), s));
        var target =
                in.preferences().goal() == null
                        ? weakest(in.skills())
                        : bySkill.get(in.preferences().goal());
        if (target == null)
            return stopped(
                    in,
                    Branch.UNAVAILABLE,
                    Reason.WRONG_GRAPH,
                    "The selected goal is not a member of this exact graph.");
        var observations =
                target.observations().stream().filter(o -> !o.at().isAfter(in.asOf())).toList();
        var latest = observations.isEmpty() ? null : observations.getLast();
        var failures = failedBehaviors(observations);
        int consecutive = failures.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int observedDifficulty = latest == null ? 1 : latest.difficulty();
        int independentDifficulty =
                observations.stream()
                        .filter(Observation::independent)
                        .mapToInt(Observation::difficulty)
                        .max()
                        .orElse(observedDifficulty);
        Branch branch;
        Reason reason;
        Action action;
        String message;
        if (target.band() == Band.UNKNOWN) {
            branch = Branch.BASELINE;
            reason = Reason.UNKNOWN_BASELINE;
            action = Action.BASELINE;
            message =
                    "Reliable direct evidence is missing; an eligible diagnostic or introductory"
                            + " practice can establish a provisional baseline.";
        } else if (consecutive >= 2) {
            branch = Branch.REPEATED_FAILURE;
            reason = Reason.REPEATED_FAILURE;
            action = Action.DECOMPOSE;
            message =
                    "Two eligible deliberate failures on the same behavior suggest decomposed or"
                            + " prerequisite practice. Rest or change direction is also available.";
        } else if (consecutive == 1 && latest != null && latest.deliberateFailure()) {
            branch = Branch.SINGLE_FAILURE;
            reason = Reason.SINGLE_FAILURE;
            action = Action.RETRY;
            message =
                    "The latest eligible attempt observed a failure; target that behavior without"
                            + " increasing difficulty.";
        } else if (latest != null
                && latest.outcome() == EvidenceTypes.Outcome.PASS
                && latest.assistance() != EvidenceTypes.Assistance.NONE_OBSERVED) {
            branch = Branch.ASSISTED;
            reason = Reason.ASSISTED_SUCCESS;
            action = Action.CONSOLIDATE;
            message =
                    "The latest success had assistance or unknown independence; consolidate and"
                            + " validate with a different family at the same or lower difficulty.";
        } else if (target.contradicted()
                || target.freshness() == Freshness.STALE
                || (target.band().ordinal() >= Band.INDEPENDENT.ordinal() && !target.current())) {
            branch = Branch.UNCERTAIN;
            reason = Reason.UNCERTAIN_SUPPORT;
            action = Action.FRESH_VALIDATION;
            message =
                    "Current capability is uncertain or stale; fresh validation is needed before"
                            + " harder work.";
        } else if (target.current()
                && target.band() == Band.TRANSFER_SUPPORTED
                && retentionReady(in, target, bySkill, independentDifficulty)) {
            branch = Branch.RETENTION;
            reason = Reason.RETENTION_DUE;
            action = Action.RETENTION;
            message =
                    "Transfer is supported within its checked scope; a reserved delayed check"
                            + " requires a seven-day quiet interval.";
        } else if (target.current() && target.band() == Band.INDEPENDENT) {
            branch = Branch.TRANSFER;
            reason = Reason.TRANSFER_READY;
            action = Action.TRANSFER;
            message =
                    "Independent capability is supported; a separate eligible held-out context can"
                            + " test transfer.";
        } else if (target.band().ordinal() < Band.INDEPENDENT.ordinal()) {
            branch = Branch.DIVERSITY;
            reason = Reason.NEED_DIVERSITY;
            action = Action.DIVERSIFY;
            message =
                    "Another unrelated independent family and complete behavior coverage are"
                            + " needed; related variants do not supply diversity.";
        } else {
            branch = Branch.SUPPORTED;
            reason = Reason.SUPPORTED_SCOPE;
            action = Action.PROGRESS;
            message =
                    "Current checked support permits one modest difficulty step or practice toward"
                            + " a prerequisite-supported next skill.";
        }
        int lastDifficulty = Math.min(observedDifficulty, independentDifficulty);
        Set<Integer> behaviors = new TreeSet<>(target.requiredBehaviors());
        if (branch == Branch.SINGLE_FAILURE || branch == Branch.REPEATED_FAILURE) {
            behaviors.clear();
            for (var entry : failures.entrySet())
                if (entry.getValue() > 0) behaviors.add(entry.getKey());
        }
        final SkillState originalTarget = target;
        // Prerequisites guide the recommendation, but exploratory PRACTICE stays available
        // explicitly.
        if (branch == Branch.REPEATED_FAILURE || branch == Branch.SUPPORTED) {
            var prerequisite =
                    target.prerequisites().stream()
                            .map(bySkill::get)
                            .filter(Objects::nonNull)
                            .filter(s -> !s.current())
                            .sorted(Comparator.comparing(SkillState::skill))
                            .findFirst();
            if (prerequisite.isPresent()) {
                target = prerequisite.get();
                behaviors = new TreeSet<>(target.requiredBehaviors());
            } else if (branch == Branch.SUPPORTED) {
                var dependent =
                        in.skills().stream()
                                .filter(s -> s.prerequisites().contains(originalTarget.skill()))
                                .filter(
                                        s ->
                                                s.prerequisites().stream()
                                                        .allMatch(
                                                                p ->
                                                                        bySkill.containsKey(p)
                                                                                && bySkill.get(p)
                                                                                        .current()))
                                .filter(s -> !s.current())
                                .sorted(Comparator.comparing(SkillState::skill))
                                .findFirst();
                if (dependent.isPresent()) {
                    target = dependent.get();
                    behaviors = new TreeSet<>(target.requiredBehaviors());
                }
            }
        }
        final var selectedTarget = target;
        final Set<Integer> required = Set.copyOf(behaviors);
        Instant retentionDue = retentionDue(originalTarget, in.asOf());
        var rejected = new TreeMap<String, List<Reason>>();
        var exploratory = new TreeSet<String>();
        var eligible = new ArrayList<Candidate>();
        for (var c : in.candidates()) {
            var reject = baseExclusions(in, c);
            boolean practice = c.cohort() == Cohort.PRACTICE;
            if (exploratoryEligible(in, c)) exploratory.add(c.key());
            if (!c.targets(target.skill(), required)) reject.add(Reason.WRONG_BEHAVIOR);
            if (!c.graph().equals(in.graph())) reject.add(Reason.WRONG_GRAPH);
            // Baseline diagnoses do not assert prerequisite mastery. Ordinary practice does not
            // unlock it.
            if (c.cohort() != Cohort.BASELINE
                    && c.prerequisites().stream()
                            .anyMatch(p -> !bySkill.containsKey(p) || !bySkill.get(p).current()))
                reject.add(Reason.PREREQUISITE);
            boolean role =
                    switch (branch) {
                        case BASELINE ->
                                (c.cohort() == Cohort.BASELINE && !c.baselineSkipped() && c.fresh())
                                        || (practice && c.difficulty() <= 2 && c.fresh());
                        case REPEATED_FAILURE, SINGLE_FAILURE -> practice;
                        case ASSISTED ->
                                practice && (latest == null || !latest.family().equals(c.family()));
                        case UNCERTAIN, DIVERSITY -> practice && c.fresh();
                        case RETENTION -> c.cohort() == Cohort.RETENTION;
                        case TRANSFER -> c.cohort() == Cohort.TRANSFER;
                        case SUPPORTED -> practice;
                        default -> false;
                    };
            if (!role) reject.add(Reason.WRONG_ROLE);
            if (branch == Branch.REPEATED_FAILURE && c.priorSubmissions() >= 2)
                reject.add(Reason.RETRY_EXHAUSTED);
            if (c.baselineSkipped() && c.cohort() == Cohort.BASELINE)
                reject.add(Reason.BASELINE_SKIPPED);
            if (Set.of(Cohort.TRANSFER, Cohort.RETENTION).contains(c.cohort())) {
                if (!c.equivalent()) reject.add(Reason.NO_EQUIVALENCE);
                if (!c.fresh()) reject.add(Reason.FAMILIAR);
                if (!target.current()) reject.add(Reason.NO_INDEPENDENT_SUPPORT);
                if (c.cohort() == Cohort.RETENTION
                        && (retentionDue == null || retentionDue.isAfter(in.asOf())))
                    reject.add(Reason.RETENTION_NOT_DUE);
            }
            int maximum =
                    switch (branch) {
                        case BASELINE -> Math.max(2, lastDifficulty);
                        case TRANSFER, RETENTION, SUPPORTED ->
                                Math.min(5, independentDifficulty + 1);
                        default -> lastDifficulty;
                    };
            if (c.difficulty() > maximum) reject.add(Reason.DIFFICULTY);
            if ((branch == Branch.DIVERSITY || branch == Branch.UNCERTAIN) && !c.fresh())
                reject.add(Reason.FAMILIAR);
            reject = new ArrayList<>(new LinkedHashSet<>(reject));
            rejected.put(c.key(), List.copyOf(reject));
            if (reject.isEmpty()) eligible.add(c);
        }
        final Branch chosenBranch = branch;
        eligible.sort(
                Comparator.comparingInt(
                                (Candidate c) -> branchPreference(chosenBranch, c, lastDifficulty))
                        .thenComparing(
                                c -> candidateDue(in, c, retentionDue),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(Candidate::difficulty)
                        .thenComparing(c -> c.challenge().id())
                        .thenComparingInt(c -> c.challenge().version())
                        .thenComparing(Candidate::variant)
                        .thenComparing(Candidate::key));
        var selections = new ArrayList<Selection>();
        for (var c : eligible)
            selections.add(
                    selection(
                            c,
                            action,
                            selectedTarget,
                            required,
                            candidateDue(in, c, retentionDue),
                            false));
        var rankings = new ArrayList<Ranking>();
        for (var c : in.candidates())
            rankings.add(
                    new Ranking(
                            c.key(),
                            rejected.get(c.key()).isEmpty(),
                            exploratory.contains(c.key()),
                            eligible.indexOf(c) + 1,
                            rejected.get(c.key()),
                            candidateDue(in, c, retentionDue)));
        var alternatives = new ArrayList<Selection>();
        if (selections.size() > 1) alternatives.addAll(selections.subList(1, selections.size()));
        for (var c : in.candidates())
            if (exploratory.contains(c.key()) && !eligible.contains(c))
                alternatives.add(selection(c, Action.EXPLORATORY, null, Set.of(), null, true));
        var reasons = new ArrayList<Explanation>();
        reasons.add(explain(reason, message, originalTarget));
        if (!originalTarget.exclusions().isEmpty()) {
            reasons.add(
                    new Explanation(
                            Reason.UNCERTAIN_SUPPORT,
                            "Some earlier observations are excluded from this estimate; unavailable"
                                    + " or superseded evidence is not replaced with older positive"
                                    + " proof.",
                            originalTarget.exclusions().stream()
                                    .map(x -> x.contains(":") ? x.substring(0, x.indexOf(':')) : x)
                                    .distinct()
                                    .sorted()
                                    .toList(),
                            List.of(originalTarget.revision())));
        }
        if (branch == Branch.REPEATED_FAILURE)
            reasons.add(
                    explain(
                            Reason.REST_OR_CHANGE_DIRECTION,
                            "At most one decomposed task is suggested; you may rest, defer or"
                                    + " change direction without penalty.",
                            target));
        if (retentionDue != null
                && retentionDue.isAfter(in.asOf())
                && originalTarget.band() == Band.TRANSFER_SUPPORTED)
            reasons.add(
                    explain(
                            Reason.RETENTION_NOT_DUE,
                            "The seven-day quiet interval is not complete. Waiting is available;"
                                + " starting relevant practice or instruction postpones retention.",
                            originalTarget));
        if (selections.isEmpty()) {
            var relevant =
                    in.candidates().stream()
                            .filter(c -> c.targets(selectedTarget.skill(), required))
                            .filter(c -> !rejected.get(c.key()).contains(Reason.WRONG_ROLE))
                            .toList();
            boolean unavailable =
                    !relevant.isEmpty() && relevant.stream().noneMatch(Candidate::available);
            reasons.add(
                    explain(
                            unavailable ? Reason.CONTEXT_UNAVAILABLE : Reason.CATALOG_DEPLETED,
                            unavailable
                                    ? "No supported execution context is available for this"
                                            + " behavior; repair the runner/content or use ordinary"
                                            + " study."
                                    : "No candidate satisfies this branch, prerequisite, novelty,"
                                          + " budget and preference constraints. No unseen task was"
                                          + " invented.",
                            target));
            if (branch == Branch.RETENTION
                    && retentionDue != null
                    && retentionDue.isAfter(in.asOf()))
                reasons.add(
                        explain(
                                Reason.RETENTION_NOT_DUE,
                                "The seven-day quiet interval is not complete; relevant practice or"
                                        + " instruction restarts it.",
                                target));
            return new NextQuestDecision(
                    unavailable ? Branch.UNAVAILABLE : Branch.DEPLETED,
                    null,
                    alternatives,
                    rankings,
                    reasons,
                    CONTROLS);
        }
        return new NextQuestDecision(
                branch, selections.getFirst(), alternatives, rankings, reasons, CONTROLS);
    }

    private static Instant candidateDue(
            AdaptationInput in, Candidate candidate, Instant retentionDue) {
        Instant defer =
                in.candidates().stream()
                        .filter(c -> c.family().equals(candidate.family()))
                        .map(c -> in.preferences().deferred().get(c.challenge().id()))
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        if (candidate.cohort() != Cohort.RETENTION) return defer;
        return retentionDue == null
                ? defer
                : defer == null || retentionDue.isAfter(defer) ? retentionDue : defer;
    }

    private static boolean retentionReady(
            AdaptationInput in,
            SkillState target,
            Map<SkillRef, SkillState> skills,
            int difficulty) {
        Instant due = retentionDue(target, in.asOf());
        return due != null
                && !due.isAfter(in.asOf())
                && in.candidates().stream()
                        .anyMatch(
                                c ->
                                        c.cohort() == Cohort.RETENTION
                                                && c.equivalent()
                                                && c.fresh()
                                                && c.targets(
                                                        target.skill(), target.requiredBehaviors())
                                                && baseExclusions(in, c).isEmpty()
                                                && c.difficulty() <= difficulty + 1
                                                && c.prerequisites().stream()
                                                        .allMatch(
                                                                p ->
                                                                        skills.containsKey(p)
                                                                                && skills.get(p)
                                                                                        .current()));
    }

    private static boolean validPrerequisites(List<SkillState> skills) {
        var pending = new TreeMap<SkillRef, Set<SkillRef>>();
        skills.forEach(s -> pending.put(s.skill(), new TreeSet<>(s.prerequisites())));
        if (pending.values().stream().flatMap(Set::stream).anyMatch(p -> !pending.containsKey(p)))
            return false;
        while (!pending.isEmpty()) {
            var ready =
                    pending.entrySet().stream()
                            .filter(e -> e.getValue().isEmpty())
                            .map(Map.Entry::getKey)
                            .toList();
            if (ready.isEmpty()) return false;
            ready.forEach(pending::remove);
            pending.values().forEach(p -> p.removeAll(ready));
        }
        return true;
    }

    /** Shared explicit exploration rule; not a recommendation or a mastery prerequisite claim. */
    static boolean exploratoryEligible(AdaptationInput in, Candidate candidate) {
        return in.graphAvailable()
                && in.preferences().track() == Track.EVOLUTION
                && !in.preferences().paused()
                && candidate.cohort() == Cohort.PRACTICE
                && baseExclusions(in, candidate).isEmpty();
    }

    private static List<Reason> baseExclusions(AdaptationInput in, Candidate c) {
        var r = new ArrayList<Reason>();
        if (!c.available()) r.add(Reason.UNAVAILABLE);
        if (!c.graph().equals(in.graph())) r.add(Reason.WRONG_GRAPH);
        if (c.cohort() == Cohort.PILOT_POST) r.add(Reason.RESERVED_COHORT);
        if (in.candidates().stream()
                .anyMatch(
                        related ->
                                related.family().equals(c.family())
                                        && in.preferences()
                                                .declined()
                                                .contains(related.challenge().id())))
            r.add(Reason.DECLINED);
        Instant defer =
                in.candidates().stream()
                        .filter(related -> related.family().equals(c.family()))
                        .map(related -> in.preferences().deferred().get(related.challenge().id()))
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        if (defer != null && defer.isAfter(in.asOf())) r.add(Reason.DEFERRED);
        if (c.effortMinutes() > in.preferences().budgetMinutes()) r.add(Reason.BUDGET);
        if ((!c.retryAllowed() && c.variantPresentations() > 0)
                || (c.maximumPresentations() > 0
                        && c.variantPresentations() >= c.maximumPresentations()))
            r.add(Reason.RETRY_EXHAUSTED);
        return r;
    }

    private static int branchPreference(Branch b, Candidate c, int difficulty) {
        if (b == Branch.BASELINE) return c.cohort() == Cohort.BASELINE ? 0 : 1;
        if (b == Branch.REPEATED_FAILURE)
            return c.difficulty() < difficulty ? 0 : c.fresh() ? 1 : 2;
        if (b == Branch.SINGLE_FAILURE) return c.fresh() ? 0 : 1;
        return 0;
    }

    private static SkillState weakest(List<SkillState> skills) {
        return skills.stream()
                .min(
                        Comparator.comparingInt((SkillState s) -> s.current() ? 1 : 0)
                                .thenComparingInt(s -> s.prerequisites().size())
                                .thenComparing(s -> s.band().ordinal())
                                .thenComparing(SkillState::skill))
                .orElseThrow();
    }

    private static Map<Integer, Integer> failedBehaviors(List<Observation> observations) {
        var result = new TreeMap<Integer, Integer>();
        for (var o : observations) {
            if (o.independent()) o.passed().forEach(b -> result.put(b, 0));
            if (o.deliberateFailure()) o.failed().forEach(b -> result.merge(b, 1, Integer::sum));
        }
        return result;
    }

    private static Instant retentionDue(SkillState s, Instant asOf) {
        Instant latest = null;
        for (var a : s.activities()) {
            if (Collections.disjoint(a.behaviors(), s.requiredBehaviors())) continue;
            if (a.at() == null) return null;
            // A known future relevant event (clock anomaly) must not produce a ready claim.
            if (a.at().isAfter(asOf)) return null;
            if (latest == null || a.at().isAfter(latest)) latest = a.at();
        }
        return latest == null ? null : latest.plus(QUIET);
    }

    private static Selection selection(
            Candidate c,
            Action action,
            SkillState s,
            Set<Integer> behaviors,
            Instant due,
            boolean exploratory) {
        Action actual =
                action == Action.BASELINE && c.cohort() == Cohort.PRACTICE
                        ? Action.INTRODUCTION
                        : action;
        return new Selection(
                actual,
                c.quest(),
                c.challenge(),
                s == null ? c.targets().getFirst().skill() : s.skill(),
                s == null ? Set.of(c.targets().getFirst().behavior()) : behaviors,
                c.difficulty(),
                c.effortMinutes(),
                due,
                null,
                exploratory);
    }

    private static Explanation explain(Reason code, String text, SkillState s) {
        return new Explanation(
                code,
                text,
                s == null
                        ? List.of()
                        : s.observations().stream()
                                .map(Observation::evidenceId)
                                .distinct()
                                .toList(),
                s == null ? List.of() : List.of(s.revision()));
    }

    private static NextQuestDecision stopped(
            AdaptationInput in, Branch branch, Reason reason, String text) {
        return new NextQuestDecision(
                branch,
                null,
                List.of(),
                in.candidates().stream()
                        .map(c -> new Ranking(c.key(), false, false, 0, List.of(reason), null))
                        .toList(),
                List.of(explain(reason, text, null)),
                List.of("RESUME", "CHANGE_GOAL", "SWITCH_TRACK", "ORDINARY_REVIEW"));
    }
}
