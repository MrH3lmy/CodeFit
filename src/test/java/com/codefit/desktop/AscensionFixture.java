package com.codefit.desktop;

import static com.codefit.evolution.evidence.EvidenceTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository.CheckSummary;
import com.codefit.evolution.evaluation.EvaluationTypes.CheckStatus;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.mastery.EstimateInput.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Synthetic saved policy inputs, not executed learner performance. Never packaged with the engine.
 */
public final class AscensionFixture {
    public static final Instant TIME = Instant.parse("2026-06-01T12:00:00Z");
    public static final SkillDefinition ATOMIC =
            JavaDebuggingSkillSeed.graph().definitions().stream()
                    .filter(s -> s.ref().skillId().equals("java.concurrency.atomic-update"))
                    .findFirst()
                    .orElseThrow();
    private static final EvidenceMappingRef MAP = EvidenceMappingRepository.INITIAL_MAPPING;

    public static void main(String[] args) throws Exception {
        create(Path.of(args[0]), args[1]);
    }

    public static void create(Path path, String mode) throws Exception {
        LearnerFixture.create(path);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            try (var s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
            }
            c.setAutoCommit(false);
            if (mode.equals("large") || mode.equals("over-limit")) {
                large(c, mode.equals("large") ? 32 : 33);
                c.commit();
                return;
            }
            if (mode.equals("new")) {
                c.commit();
                return;
            }
            if (mode.equals("newer-graph")) {
                var original = JavaDebuggingSkillSeed.graph();
                new SkillCatalogRepository()
                        .publishGraph(
                                c,
                                new SkillGraph(
                                        new GraphRef(original.ref().graphId(), 2),
                                        original.definitions(),
                                        original.prerequisites()));
                c.commit();
                return;
            }
            var first = observation(1, "fixture-first", 0, false);
            var second = observation(2, "fixture-second", 1, false);
            var observations = new ArrayList<Observation>();
            observations.add(first);
            if (!mode.equals("insufficient")) observations.add(second);
            if (mode.equals("contradictory"))
                observations.add(observation(3, "fixture-third", 2, true));
            for (var o : observations) record(c, o);
            Instant asOf = TIME.plus(Duration.ofDays(mode.equals("stale") ? 40 : 4));
            var input =
                    new EstimateInput(
                            JavaDebuggingSkillSeed.GRAPH,
                            ATOMIC,
                            new Epoch("initial", Instant.MIN),
                            asOf,
                            List.of(MAP),
                            observations,
                            List.of(),
                            List.of());
            save(c, input, OrdinalMasteryPolicy.VERSION);
            switch (mode) {
                case "supported", "insufficient", "stale", "contradictory" -> {}
                case "withdrawn", "revised" -> {
                    var old = first.facts().source();
                    var newer = new EvidenceSourceRef(old.type(), old.recordId(), 2);
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " evolution_evidence_observations(evidence_id,root_id,revision,recorded_at,previous_id,change_kind,payload)"
                                        + " VALUES(?,?,2,?,?,?,?)")) {
                        s.setString(1, EvidenceIds.id(newer));
                        s.setString(2, EvidenceIds.root(old));
                        s.setString(3, asOf.toString());
                        s.setString(4, first.id());
                        s.setString(5, mode.equals("withdrawn") ? "RETRACTION" : "SUPERSESSION");
                        s.setBytes(6, new byte[] {1});
                        s.executeUpdate();
                    }
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE evolution_evidence_sources SET head_id=? WHERE"
                                        + " root_id=?")) {
                        s.setString(1, EvidenceIds.id(newer));
                        s.setString(2, EvidenceIds.root(old));
                        s.executeUpdate();
                    }
                }
                case "outdated" -> {
                    var v2 =
                            new SkillDefinition(
                                    new SkillRef(ATOMIC.ref().skillId(), 2),
                                    ATOMIC.domainId(),
                                    ATOMIC.name(),
                                    ATOMIC.description(),
                                    ATOMIC.observableBehaviors(),
                                    ATOMIC.lifecycle());
                    new SkillCatalogRepository().publishDefinition(c, v2);
                    save(
                            c,
                            new EstimateInput(
                                    input.graph(),
                                    v2,
                                    input.epoch(),
                                    input.asOf(),
                                    input.mappings(),
                                    input.observations(),
                                    input.activities(),
                                    input.unavailableMappings()),
                            OrdinalMasteryPolicy.VERSION);
                }
                case "unknown-policy" -> save(c, input, "future-policy");
                case "future" ->
                        save(
                                c,
                                new EstimateInput(
                                        input.graph(),
                                        input.skill(),
                                        input.epoch(),
                                        Instant.parse("2099-01-01T00:00:00Z"),
                                        input.mappings(),
                                        input.observations(),
                                        input.activities(),
                                        input.unavailableMappings()),
                                OrdinalMasteryPolicy.VERSION);
                case "epoch" -> {
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " evolution_estimate_epochs(id,graph_id,graph_version,started_at,evidence_through)"
                                        + " VALUES('reset-fixture',?,1,?,3)")) {
                        s.setString(1, input.graph().graphId());
                        s.setString(2, asOf.toString());
                        s.executeUpdate();
                    }
                }
                case "mapping" -> {
                    var repo = new EvidenceMappingRepository();
                    var old = repo.find(c, MAP).orElseThrow();
                    repo.publish(
                            c,
                            new EvidenceMapping(
                                    new EvidenceMappingRef(MAP.mappingId(), 2),
                                    old.graph(),
                                    old.rules()));
                }
                default -> throw new IllegalArgumentException("Unknown fixture mode");
            }
            c.commit();
        }
    }

    public static void save(Connection c, EstimateInput input, String policy) throws Exception {
        byte[] bytes = EstimateCodec.encode(input);
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_estimate_revisions(input_sha,policy,graph_id,graph_version,skill_id,skill_version,epoch_id,as_of,inputs)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, CatalogCodec.digest(bytes));
            s.setString(2, policy);
            s.setString(3, input.graph().graphId());
            s.setInt(4, input.graph().graphVersion());
            s.setString(5, input.skill().ref().skillId());
            s.setInt(6, input.skill().ref().definitionVersion());
            s.setString(7, input.epoch().id());
            s.setString(8, input.asOf().toString());
            s.setBytes(9, bytes);
            s.executeUpdate();
        }
    }

    private static Observation observation(long id, String family, int day, boolean failed) {
        var at = TIME.plus(Duration.ofDays(day));
        var source = new EvidenceSourceRef(SourceType.CHALLENGE_EVALUATION, id, 1);
        var check =
                new CheckSummary(
                        "fixture-functional",
                        CheckKind.DETERMINISTIC_IO,
                        true,
                        List.of(new BehaviorTarget(ATOMIC.ref(), 0)),
                        failed ? CheckStatus.FAILED : CheckStatus.PASSED,
                        1,
                        "PRIVATE-CHECK-LIMITATION");
        var evaluation =
                new EvaluationReference(
                        "fixture-submission-" + id,
                        family,
                        "fixture-rubric/v1",
                        "local-java-v1",
                        "a".repeat(64),
                        List.of(check.id()),
                        id);
        var facts =
                new EvidenceObservation(
                        source,
                        new EvidenceTime(at.toString(), at, TimeBasis.EXACT_UTC),
                        Provenance.LOCAL_CHECK,
                        failed ? Outcome.FAIL : Outcome.PASS,
                        Assistance.NONE_OBSERVED,
                        null,
                        Exposure.FIRST_PRESENTATION,
                        Set.of(),
                        Availability.AVAILABLE,
                        "PRIVATE-OUTCOME",
                        "LOCAL",
                        family,
                        evaluation,
                        Change.ORIGINAL,
                        null,
                        ChangeReason.ORIGINAL_RECORD,
                        Actor.SYSTEM);
        var context =
                new Context(
                        new CatalogRef("fixture-quest", 1),
                        new CatalogRef("fixture-challenge-" + id, 1),
                        family,
                        "fixture-variant-" + id,
                        Cohort.PRACTICE,
                        true,
                        true,
                        "fixture-presentation-" + id,
                        at,
                        List.of(check));
        return new Observation(EvidenceIds.id(source), id, MAP, facts, List.of(), context);
    }

    private static void record(Connection c, Observation o) throws Exception {
        String root = EvidenceIds.root(o.facts().source());
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_evidence_sources(root_id,source_type,source_record_id)"
                            + " VALUES(?,'CHALLENGE_EVALUATION',?)")) {
            s.setString(1, root);
            s.setLong(2, o.facts().source().recordId());
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_evidence_observations(sequence,evidence_id,root_id,revision,recorded_at,change_kind,payload)"
                            + " VALUES(?,?,?,1,?,'ORIGINAL',?)")) {
            s.setLong(1, o.sequence());
            s.setString(2, o.id());
            s.setString(3, root);
            s.setString(4, TIME.toString());
            s.setBytes(5, EvidenceCodec.encode(o.facts()));
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement(
                        "UPDATE evolution_evidence_sources SET head_id=? WHERE root_id=?")) {
            s.setString(1, o.id());
            s.setString(2, root);
            s.executeUpdate();
        }
    }

    /**
     * Synthetic wider published graph; test-only guard removal, never part of a production
     * migration.
     */
    private static void large(Connection c, int count) throws Exception {
        var names = new ArrayList<String>();
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_schema WHERE type='trigger' AND"
                                    + " tbl_name='evolution_graph_skills'")) {
            while (r.next()) names.add(r.getString(1));
        }
        try (var s = c.createStatement()) {
            for (String name : names)
                s.execute("DROP TRIGGER \"" + name.replace("\"", "\"\"") + "\"");
        }
        for (int i = 4; i < count; i++) {
            var def =
                    new SkillDefinition(
                            new SkillRef("fixture.skill-" + i, 1),
                            "software-engineering",
                            "Engineering capability " + i,
                            "Original bounded fixture definition " + i,
                            List.of("Demonstrate behavior " + i),
                            SkillLifecycle.ACTIVE);
            new SkillCatalogRepository().publishDefinition(c, def);
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " evolution_graph_skills(graph_id,graph_version,skill_id,definition_version)"
                                + " VALUES(?,1,?,1)")) {
                s.setString(1, JavaDebuggingSkillSeed.GRAPH.graphId());
                s.setString(2, def.ref().skillId());
                s.executeUpdate();
            }
        }
    }
}
