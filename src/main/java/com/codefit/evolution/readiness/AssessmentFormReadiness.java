package com.codefit.evolution.readiness;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.quest.*;

import java.util.*;

/**
 * Bounded metadata comparison for independent content review. Parity is necessary, never proof of
 * equivalent difficulty, human approval, assignment authorization or pilot readiness. No grading,
 * learner records, clocks, persistence, source text, hidden cases or network calls.
 */
public final class AssessmentFormReadiness {
    public enum Status {
        READY,
        NOT_READY
    }

    public enum Invariant {
        ATOMIC_OPERATION,
        REJECTION_NO_MUTATION,
        EXACTLY_ONCE_EFFECT
    }

    public record InvariantBinding(Invariant invariant, String checkId, BehaviorTarget behavior) {}

    public record Form(ChallengeSpec specification, List<InvariantBinding> invariantBindings) {
        public Form {
            Objects.requireNonNull(specification);
            invariantBindings = List.copyOf(invariantBindings);
            if (invariantBindings.size() > 64
                    || new HashSet<>(invariantBindings).size() != invariantBindings.size())
                throw new IllegalArgumentException("Unique bounded invariant bindings required");
            for (var binding : invariantBindings) {
                var check =
                        specification.rubric().checks().stream()
                                .filter(c -> c.id().equals(binding.checkId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Invariant check does not resolve"));
                if (!check.required()
                        || !check.targets().contains(binding.behavior())
                        || check.kind() == CheckKind.REFLECTION)
                    throw new IllegalArgumentException(
                            "Invariant must map to a required observable behavior check");
            }
        }
    }

    /** Digests/IDs only: safe review metadata, not a challenge-content presentation API. */
    public record Summary(
            CatalogRef challenge,
            CatalogRef rubric,
            GraphRef graph,
            Cohort cohort,
            String family,
            String variant,
            String manifestSha256,
            String rubricSha256,
            Map<String, String> assetDigests,
            List<BehaviorTarget> behaviors,
            boolean installed) {
        public Summary {
            assetDigests = Collections.unmodifiableMap(new TreeMap<>(assetDigests));
            behaviors = List.copyOf(behaviors);
        }
    }

    public record Comparison(
            Summary baseline,
            Summary post,
            boolean mappedCheckParity,
            boolean declaredInvariantParity,
            List<String> gaps) {
        public Comparison {
            gaps = List.copyOf(gaps);
        }

        public boolean structuralParity() {
            return gaps.isEmpty();
        }

        public String interpretation() {
            return "Structural parity only; independent human equivalence review and protocol"
                    + " freeze remain required.";
        }
    }

    public record Report(
            String version,
            List<Summary> inventory,
            List<Summary> candidates,
            Map<Cohort, Integer> distinctFamilies,
            List<Comparison> comparisons,
            List<String> blockers,
            Status technicalImplementation,
            Status protocolFreeze,
            Status pilotExecution) {
        public Report {
            inventory = List.copyOf(inventory);
            candidates = List.copyOf(candidates);
            distinctFamilies = Collections.unmodifiableMap(new EnumMap<>(distinctFamilies));
            comparisons = List.copyOf(comparisons);
            blockers = List.copyOf(blockers);
        }
    }

    public Summary summarize(ChallengeSpec spec) {
        var digests = new TreeMap<String, String>();
        spec.assets().forEach(a -> digests.put(a.id(), a.sha256()));
        return new Summary(
                spec.ref(),
                spec.rubric().ref(),
                spec.graph(),
                spec.cohort(),
                spec.familyId(),
                spec.variantId(),
                CatalogCodec.digest(CatalogCodec.encode(spec)),
                CatalogCodec.digest(CatalogCodec.encode(spec.rubric())),
                digests,
                spec.targets(),
                JavaTrainingPack.challenges().contains(spec));
    }

    public Comparison compare(SkillGraph graph, Form baseline, Form post) {
        var a = baseline.specification();
        var b = post.specification();
        var gaps = new ArrayList<String>();
        if (a.cohort() != Cohort.BASELINE || b.cohort() != Cohort.PILOT_POST)
            gaps.add("ROLE_MISMATCH");
        if (!a.graph().equals(graph.ref()) || !b.graph().equals(graph.ref()))
            gaps.add("EXACT_GRAPH_MISMATCH");
        if (!a.domainId().equals(b.domainId()) || a.runner() != b.runner())
            gaps.add("DOMAIN_OR_RUNNER_MISMATCH");
        for (var spec : List.of(a, b))
            for (var target : spec.targets()) {
                var skill =
                        graph.definitions().stream()
                                .filter(s -> s.ref().equals(target.skill()))
                                .findFirst();
                if (skill.isEmpty()
                        || target.behavior() >= skill.get().observableBehaviors().size())
                    gaps.add("EXACT_BEHAVIOR_MISSING:" + target);
            }
        if (a.ref().equals(b.ref())
                || a.variantId().equals(b.variantId())
                || a.familyId().equals(b.familyId())) gaps.add("RELATED_OR_REUSED_FORM");
        if (a.difficulty() != b.difficulty() || a.effortMinutes() != b.effortMinutes())
            gaps.add("AUTHORED_DEMAND_BOUNDS_DIFFER");
        boolean checks =
                new HashSet<>(a.targets()).equals(new HashSet<>(b.targets()))
                        && profile(a).equals(profile(b));
        if (!checks) gaps.add("REQUIRED_BEHAVIOR_CHECK_PROFILE_DIFFERS");
        boolean invariants =
                !baseline.invariantBindings().isEmpty()
                        && invariants(baseline).equals(invariants(post));
        if (!invariants) gaps.add("DECLARED_REQUIRED_INVARIANTS_DIFFER");
        return new Comparison(summarize(a), summarize(b), checks, invariants, gaps);
    }

    private Map<BehaviorTarget, Map<CheckKind, Integer>> profile(ChallengeSpec spec) {
        var result = new HashMap<BehaviorTarget, Map<CheckKind, Integer>>();
        for (var check : spec.rubric().checks())
            if (check.required())
                for (var target : check.targets())
                    result.computeIfAbsent(target, k -> new EnumMap<>(CheckKind.class))
                            .merge(check.kind(), 1, Integer::sum);
        return result;
    }

    private Map<BehaviorTarget, Set<Invariant>> invariants(Form form) {
        var out = new HashMap<BehaviorTarget, Set<Invariant>>();
        for (var binding : form.invariantBindings())
            out.computeIfAbsent(binding.behavior(), k -> EnumSet.noneOf(Invariant.class))
                    .add(binding.invariant());
        return out;
    }

    /** Explicit author declarations, tied to actual check IDs; not inferred from a cohort label. */
    public static Form declared(ChallengeSpec spec) {
        var atomic = new BehaviorTarget(new SkillRef("java.concurrency.atomic-update", 1), 0);
        var invariant = new BehaviorTarget(new SkillRef("java.concurrency.invariant", 1), 0);
        var bindings = new ArrayList<InvariantBinding>();
        boolean known =
                JavaTrainingPack.challenges().contains(spec)
                        || PilotAssessmentForms.baselineCapacity().equals(spec);
        if (known
                && Set.of("baseline-counter", "baseline-capacity", "post-ledger", "post-expiry")
                        .contains(spec.ref().id())
                && spec.ref().version() == 1) {
            bindings.add(
                    new InvariantBinding(Invariant.ATOMIC_OPERATION, "operation-boundary", atomic));
            if (!spec.ref().id().equals("baseline-counter")) {
                bindings.add(
                        new InvariantBinding(
                                Invariant.REJECTION_NO_MUTATION, "functional", invariant));
                bindings.add(
                        new InvariantBinding(
                                Invariant.EXACTLY_ONCE_EFFECT, "functional", invariant));
            }
        }
        return new Form(spec, bindings);
    }

    public Report installedReport(SkillGraph exactGraph) {
        var installed = JavaTrainingPack.challenges();
        if (installed.size() > 64)
            throw new IllegalStateException("Readiness inventory exceeds V1 bound");
        var candidate = PilotAssessmentForms.baselineCapacity();
        var original =
                installed.stream()
                        .filter(s -> s.ref().equals(new CatalogRef("baseline-counter", 1)))
                        .findFirst()
                        .orElseThrow();
        var comparisons = new ArrayList<Comparison>();
        for (String post : List.of("post-ledger", "post-expiry")) {
            var spec =
                    installed.stream()
                            .filter(s -> s.ref().equals(new CatalogRef(post, 1)))
                            .findFirst()
                            .orElseThrow();
            comparisons.add(compare(exactGraph, declared(original), declared(spec)));
            comparisons.add(compare(exactGraph, declared(candidate), declared(spec)));
        }
        var counts = new EnumMap<Cohort, Integer>(Cohort.class);
        for (var cohort : Cohort.values())
            counts.put(
                    cohort,
                    (int)
                            installed.stream()
                                    .filter(s -> s.cohort() == cohort)
                                    .map(ChallengeSpec::familyId)
                                    .distinct()
                                    .count());
        return new Report(
                PilotAssessmentForms.VERSION,
                installed.stream().map(this::summarize).toList(),
                List.of(summarize(candidate)),
                counts,
                comparisons,
                List.of(
                        "Legacy baseline/post behavior gap remains; candidate is not published or"
                                + " evaluator-authorized.",
                        "Trace baselines share a family; transfer and retention have one installed"
                                + " family each.",
                        "No frozen participant assignment, PRACTICE-only study adapter or"
                                + " cohort-wide feedback embargo.",
                        "Active-time parity, consent/export and complete freeze record are"
                                + " unverified.",
                        "Independent human content/rubric approval and study authorization are"
                                + " absent."),
                Status.NOT_READY,
                Status.NOT_READY,
                Status.NOT_READY);
    }
}
