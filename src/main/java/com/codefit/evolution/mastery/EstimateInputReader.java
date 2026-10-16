package com.codefit.evolution.mastery;

import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.EstimateInput.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.repository.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Bounded keyset collection on ONE caller-owned SQLite read snapshot. No policy work here. */
final class EstimateInputReader {
    private final EvidenceRepository evidence = new EvidenceRepository();
    private final EvidenceMappingRepository mappings = new EvidenceMappingRepository();
    private final DurableEvaluationRepository evaluations = new DurableEvaluationRepository();
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();

    EstimateInput read(
            Connection c,
            GraphRef graph,
            SkillRef skill,
            List<EvidenceMappingRef> refs,
            Instant asOf)
            throws SQLException {
        if (refs.stream().map(EvidenceMappingRef::mappingId).distinct().count()
                != refs.stream().distinct().count())
            throw new IllegalArgumentException("Select one exact version per mapping lineage");
        var definition =
                new SkillCatalogRepository()
                                .findGraph(c, graph)
                                .orElseThrow(
                                        () -> new IllegalArgumentException("Unknown exact graph"))
                                .definitions()
                                .stream()
                                .filter(d -> d.ref().equals(skill))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Skill is not in exact graph"));
        var epoch = new EstimateRevisionRepository().epoch(c, graph, asOf);
        var observations = new ArrayList<Observation>();
        var missing = new ArrayList<String>();
        var cache = new HashMap<String, Optional<DurableEvaluationRepository.EvaluationInput>>();
        for (var ref :
                refs.stream()
                        .distinct()
                        .sorted(
                                Comparator.comparing(EvidenceMappingRef::mappingId)
                                        .thenComparingInt(EvidenceMappingRef::version))
                        .toList()) {
            var mapping = mappings.find(c, ref);
            if (mapping.isEmpty()) {
                missing.add(ref.toString());
                continue;
            }
            if (!mapping.get().graph().equals(graph))
                throw new IllegalArgumentException("Mapping belongs to another exact graph");
            if (mapping.get().rules().stream().anyMatch(rule -> rule.target().equals(skill))
                    && mappings.hasPendingInterpretations(c, ref, skill))
                missing.add(ref + ":pending-interpretations");
            long cursor = 0;
            while (true) {
                var page = evidence.forSkill(c, skill, ref, cursor, 128);
                if (page.isEmpty()) break;
                for (var view : page) {
                    var stored = view.evidence();
                    var o = stored.observation();
                    Context context = null;
                    var excluded =
                            new ArrayList<>(view.exclusions().stream().map(Enum::name).toList());
                    if (view.currentAvailability() != EvidenceTypes.Availability.AVAILABLE)
                        excluded.add(view.currentAvailability().name());
                    if (view.effective() && o.evaluation() != null) {
                        String command = o.evaluation().submissionId();
                        var input = cache.get(command);
                        if (input == null) {
                            input = evaluations.evaluationInput(c, command);
                            cache.put(command, input);
                        }
                        if (input.isPresent() && input.get().observation().equals(o)) {
                            var in = input.get();
                            var p = in.presentation();
                            var prior = p.prior();
                            var current = in.exposureAtSubmission();
                            boolean novel =
                                    p.declaration() == Familiarity.DECLARED_UNFAMILIAR
                                            && prior.familyPresentations() == 0
                                            && prior.variantPresentations() == 0
                                            && !prior.knownFamiliar()
                                            && !prior.solutionRevealed()
                                            && prior.highestHint() == 0
                                            && !current.knownFamiliar()
                                            && !current.solutionRevealed()
                                            && current.highestHint() == 0
                                            && current.familyPresentations() == 1
                                            && current.variantPresentations() == 1
                                            && in.priorFamilySubmissions() == 0;
                            context =
                                    new Context(
                                            in.quest(),
                                            in.challenge(),
                                            p.familyId(),
                                            p.variantId(),
                                            in.cohort(),
                                            novel,
                                            equivalent(in.challenge(), graph, skill),
                                            p.id(),
                                            p.presentedAt(),
                                            in.checks());
                        }
                    }
                    observations.add(
                            new Observation(
                                    stored.id(),
                                    stored.sequence(),
                                    ref,
                                    o,
                                    excluded,
                                    context,
                                    evidence.firstSourceSequence(c, o.source())));
                    if (observations.size() > 10000)
                        throw new IllegalStateException(
                                "Estimate history exceeds V1 bounded snapshot; no partial estimate"
                                        + " published");
                }
                cursor = page.getLast().evidence().sequence();
            }
        }
        return new EstimateInput(
                graph,
                definition,
                epoch,
                asOf,
                refs,
                observations,
                activities(c, graph, skill, asOf, observations, refs),
                missing);
    }

    /**
     * Curated exact held-out equivalence from the approved pilot plan, never inferred from an ID.
     */
    private static boolean equivalent(CatalogRef ref, GraphRef graph, SkillRef skill) {
        if (!graph.equals(com.codefit.config.JavaDebuggingSkillSeed.GRAPH)
                || ref.version() != 1
                || !Set.of("inventory-transfer", "queue-retention").contains(ref.id()))
            return false;
        return JavaTrainingPack.challenges().stream()
                .filter(s -> s.ref().equals(ref) && s.graph().equals(graph))
                .anyMatch(s -> s.targets().stream().anyMatch(t -> t.skill().equals(skill)));
    }

    private List<Activity> activities(
            Connection c,
            GraphRef graph,
            SkillRef skill,
            Instant asOf,
            List<Observation> observations,
            List<EvidenceMappingRef> refs)
            throws SQLException {
        var result = new ArrayList<Activity>();
        // Numeric rowid keysets avoid timestamp text ordering (fractional UTC instants differ in
        // length).
        long cursor = 0;
        while (true) {
            int count = 0;
            try (var s =
                    c.prepareStatement(
                            "SELECT rowid,id,challenge_id,challenge_version,presented_at FROM"
                                + " evolution_challenge_presentations WHERE rowid>? ORDER BY rowid"
                                + " LIMIT 128")) {
                s.setLong(1, cursor);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        cursor = r.getLong(1);
                        count++;
                        var spec =
                                catalog.findChallenge(
                                                c, new CatalogRef(r.getString(3), r.getInt(4)))
                                        .orElseThrow();
                        var behaviors = targets(spec, graph, skill);
                        if (behaviors.isEmpty()) continue;
                        String presentation = r.getString(2);
                        var at = Instant.parse(r.getString(5));
                        if (!at.isAfter(asOf))
                            result.add(new Activity("presentation:" + presentation, at, behaviors));
                        long eventCursor = 0;
                        while (true) {
                            int eventCount = 0;
                            try (var events =
                                    c.prepareStatement(
                                            "SELECT rowid,id,occurred_at FROM"
                                                + " evolution_challenge_exposure_events WHERE"
                                                + " presentation_id=? AND action IN"
                                                + " ('HINT','SOLUTION','PRACTICE_CHECK') AND rowid>? ORDER BY rowid"
                                                + " LIMIT 128")) {
                                events.setString(1, presentation);
                                events.setLong(2, eventCursor);
                                try (var e = events.executeQuery()) {
                                    while (e.next()) {
                                        eventCursor = e.getLong(1);
                                        eventCount++;
                                        var time = Instant.parse(e.getString(3));
                                        if (!time.isAfter(asOf))
                                            result.add(
                                                    new Activity(
                                                            "instruction:" + e.getString(2),
                                                            time,
                                                            behaviors));
                                        if (result.size() > 10000)
                                            throw new IllegalStateException(
                                                    "Too much instruction history for bounded V1"
                                                            + " estimate");
                                    }
                                }
                            }
                            if (eventCount < 128) break;
                        }
                        if (result.size() > 10000)
                            throw new IllegalStateException(
                                    "Too much exposure history for bounded V1 estimate");
                    }
                }
            }
            if (count < 128) break;
        }
        // Include submitted attempts even when subsequently invalidated: rehearsal was still real.
        for (var o : observations) {
            var at = o.facts().time().observedAt();
            var e = o.facts().evaluation();
            if (at != null && !at.isAfter(asOf))
                result.add(
                        new Activity(
                                e == null ? "evidence:" + o.id() : "submission:" + e.submissionId(),
                                at,
                                allBehaviors(c, graph, skill, o)));
        }
        // Prepared/interrupted commands are rehearsal too. Only catalog references/timestamps are
        // read.
        cursor = 0;
        while (true) {
            var page = evaluations.submissionExposurePage(c, cursor, 128);
            for (var submission : page) {
                var spec = catalog.findChallenge(c, submission.challenge()).orElseThrow();
                var behaviors = targets(spec, graph, skill);
                if (!behaviors.isEmpty() && !submission.submittedAt().isAfter(asOf))
                    result.add(
                            new Activity(
                                    "submission:" + submission.command(),
                                    submission.submittedAt(),
                                    behaviors));
                if (result.size() > 10000)
                    throw new IllegalStateException(
                            "Too much rehearsal history for bounded V1 estimate");
            }
            if (page.size() < 128) break;
            cursor = page.getLast().id();
        }
        // Incremental ingestion can lag. Use the EXISTING source adapter to account for recorded
        // contextual rehearsal that has not been interpreted yet; this does not create evidence.
        var selected = new ArrayList<EvidenceMapping>();
        for (var ref : refs.stream().distinct().toList())
            mappings.find(c, ref).ifPresent(selected::add);
        // Include the pilot recall mapping even before its first evidence batch.
        if (graph.equals(com.codefit.config.JavaDebuggingSkillSeed.GRAPH))
            mappings.find(c, EvidenceMappingRepository.INITIAL_MAPPING).ifPresent(selected::add);
        var sourceReader = new LearningEvidenceSourceRepository();
        var all = new TreeSet<Integer>();
        new SkillCatalogRepository()
                .findGraph(c, graph).orElseThrow().definitions().stream()
                        .filter(d -> d.ref().equals(skill))
                        .findFirst()
                        .ifPresent(
                                d -> {
                                    for (int i = 0; i < d.observableBehaviors().size(); i++)
                                        all.add(i);
                                });
        for (var type :
                List.of(
                        EvidenceTypes.SourceType.REVIEW,
                        EvidenceTypes.SourceType.ASSESSMENT_ATTEMPT,
                        EvidenceTypes.SourceType.PROBLEM_ATTEMPT)) {
            var rules =
                    selected.stream()
                            .flatMap(m -> m.rules().stream())
                            .filter(r -> r.sourceType() == type && r.target().equals(skill))
                            .toList();
            if (rules.isEmpty()) continue;
            long after = 0;
            int scanned = 0;
            while (true) {
                var page =
                        sourceReader.batchForSelectors(
                                c,
                                type,
                                after,
                                128,
                                rules.stream()
                                        .map(EvidenceMapping.Rule::selector)
                                        .collect(java.util.stream.Collectors.toSet()));
                for (var source : page) {
                    if (rules.stream().noneMatch(r -> r.selector().equals(source.selector())))
                        continue;
                    var at = source.observation().time().observedAt();
                    if (at == null || !at.isAfter(asOf))
                        result.add(
                                new Activity(
                                        "source:" + EvidenceIds.root(source.observation().source()),
                                        at,
                                        all));
                }
                scanned += page.size();
                if (scanned > 10000 || result.size() > 10000)
                    throw new IllegalStateException(
                            "Rehearsal history exceeds bounded V1 input; no retention assumption"
                                    + " made");
                if (page.size() < 128) break;
                after = page.getLast().observation().source().recordId();
            }
        }
        return result;
    }

    private Set<Integer> allBehaviors(Connection c, GraphRef graph, SkillRef skill, Observation o)
            throws SQLException {
        // Legacy contextual mappings do not identify behaviors: conservatively interrupt the quiet
        // period.
        if (o.context() == null) {
            var definition =
                    new SkillCatalogRepository()
                            .findGraph(c, graph).orElseThrow().definitions().stream()
                                    .filter(d -> d.ref().equals(skill))
                                    .findFirst()
                                    .orElseThrow();
            var result = new TreeSet<Integer>();
            for (int i = 0; i < definition.observableBehaviors().size(); i++) result.add(i);
            return result;
        }
        var result = new TreeSet<Integer>();
        o.context()
                .checks()
                .forEach(
                        ch ->
                                ch.targets().stream()
                                        .filter(t -> t.skill().equals(skill))
                                        .forEach(t -> result.add(t.behavior())));
        return result;
    }

    private static Set<Integer> targets(ChallengeSpec spec, GraphRef graph, SkillRef skill) {
        if (!spec.graph().equals(graph)) return Set.of();
        var result = new TreeSet<Integer>();
        spec.targets().stream()
                .filter(t -> t.skill().equals(skill))
                .forEach(t -> result.add(t.behavior()));
        return result;
    }
}
