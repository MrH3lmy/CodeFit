package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.CatalogCodec;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Bounded historical metadata only, read on the caller's frozen SQLite transaction. */
final class LearnerSkillProjection {
    static final int MAX_NODES = 32, MAX_EDGES = 128, MAX_BEHAVIORS = 16;
    static final int MAX_INPUT_BYTES = 1024 * 1024, MAX_TOTAL_INPUT_BYTES = 8 * MAX_INPUT_BYTES;
    static final int MAX_HISTORY = 512, MAX_GRAPH_TEXT = 24000;
    private static final GraphRef GRAPH = JavaDebuggingSkillSeed.GRAPH;

    record Ref(String id, int version) {}

    record Count(String code, int count) {}

    record Edge(Ref prerequisite, Ref dependent) {}

    record Estimate(
            String status,
            String reason,
            Long revision,
            Instant asOf,
            String policyVersion,
            String epochId,
            String band,
            String historicalBand,
            String support,
            String freshness,
            Boolean contradicted,
            Boolean regressionCap,
            Integer independentFamilies,
            List<Integer> supportedBehaviors,
            Instant lastMeaningfulObservation,
            int contributionCount,
            int exclusionCount,
            List<Count> provenanceCounts,
            List<Count> assistanceCounts,
            List<Count> exposureCounts,
            List<String> reasons,
            List<String> warnings) {
        static Estimate absent(String status, String reason) {
            return new Estimate(
                    status, reason, null, null, null, null, null, null, null, null, null, null,
                    null, List.of(), null, 0, 0, List.of(), List.of(), List.of(), List.of(),
                    List.of());
        }
    }

    record Node(
            Ref ref,
            String domainId,
            String name,
            String description,
            List<String> behaviors,
            String lifecycle,
            Estimate estimate) {}

    record Projection(
            String profileId,
            String sessionId,
            Instant capturedAt,
            String status,
            String reason,
            Ref graph,
            boolean newerGraphAvailable,
            List<Node> nodes,
            List<Edge> edges) {}

    private static final class Unavailable extends RuntimeException {
        final String code;

        Unavailable(String code) {
            this.code = code;
        }
    }

    static Projection read(Connection c, String profile, String session, Instant capturedAt)
            throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Caller-owned read snapshot required");
        try {
            tables(
                    c,
                    List.of(
                            "evolution_skill_graphs",
                            "evolution_graph_skills",
                            "evolution_skill_definitions",
                            "evolution_skill_behaviors",
                            "evolution_skill_prerequisites"));
            SkillGraph graph = graph(c);
            boolean newer;
            try (var s =
                    c.prepareStatement(
                            "SELECT 1 FROM evolution_skill_graphs WHERE graph_id=? AND"
                                + " typeof(graph_version)='integer' AND graph_version>? AND"
                                + " published=1 LIMIT 1")) {
                bindGraph(s);
                try (var r = s.executeQuery()) {
                    newer = r.next();
                }
            }
            boolean estimateTables = true;
            try {
                tables(
                        c,
                        List.of(
                                "evolution_estimate_revisions",
                                "evolution_estimate_epochs",
                                "evolution_evidence_sources",
                                "evolution_evidence_observations",
                                "evolution_evidence_mappings"));
            } catch (Unavailable absent) {
                estimateTables = false;
            }
            List<Node> nodes = new ArrayList<>();
            long[] budget = {0};
            for (var definition : graph.topologicalOrder()) {
                var estimate =
                        estimateTables
                                ? estimate(c, graph, definition, capturedAt, budget)
                                : Estimate.absent("unavailable", "REVISION_INVALID");
                nodes.add(
                        new Node(
                                ref(definition.ref()),
                                definition.domainId(),
                                definition.name(),
                                definition.description(),
                                definition.observableBehaviors(),
                                definition.lifecycle().name(),
                                estimate));
            }
            return new Projection(
                    profile,
                    session,
                    capturedAt,
                    "available",
                    null,
                    new Ref(graph.ref().graphId(), graph.ref().graphVersion()),
                    newer,
                    List.copyOf(nodes),
                    graph.prerequisites().stream()
                            .map(e -> new Edge(ref(e.prerequisite()), ref(e.dependent())))
                            .toList());
        } catch (Unavailable e) {
            return unavailable(profile, session, capturedAt, e.code);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return unavailable(profile, session, capturedAt, "GRAPH_INVALID");
        }
    }

    private static Projection unavailable(
            String profile, String session, Instant at, String reason) {
        return new Projection(
                profile, session, at, "unavailable", reason, null, false, List.of(), List.of());
    }

    /** Reject views/virtual tables before any projection SELECT reaches their contents. */
    private static void tables(Connection c, List<String> names) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT type, CASE WHEN length(CAST(sql AS BLOB))<=65536"
                                + " THEN sql END FROM sqlite_schema WHERE name=? LIMIT 2")) {
            for (String name : names) {
                s.setString(1, name);
                try (var r = s.executeQuery()) {
                    if (!r.next() || !"table".equals(r.getString(1))) invalid("GRAPH_INVALID");
                    String sql = r.getString(2);
                    if (sql == null
                            || !sql.stripLeading()
                                    .toUpperCase(Locale.ROOT)
                                    .startsWith("CREATE TABLE")
                            || r.next()) invalid("GRAPH_INVALID");
                }
            }
        }
    }

    static SkillGraph graph(Connection c) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT typeof(graph_version),typeof(published) FROM evolution_skill_graphs"
                            + " WHERE graph_id=? AND graph_version=? AND published=1 LIMIT 2")) {
            bindGraph(s);
            try (var r = s.executeQuery()) {
                if (!r.next()) invalid("GRAPH_UNAVAILABLE");
                if (!"integer".equals(r.getString(1))
                        || !"integer".equals(r.getString(2))
                        || r.next()) invalid("GRAPH_INVALID");
            }
        }
        List<SkillDefinition> definitions = new ArrayList<>();
        int totalText = 0;
        // Every text expression is size/type guarded before JDBC materialization; row limits bound
        // joins.
        String query =
                "SELECT "
                        + textColumn("m.skill_id", 128)
                        + ",m.definition_version,"
                        + "typeof(m.definition_version),"
                        + textColumn("d.domain_id", 128)
                        + ","
                        + textColumn("d.name", 160)
                        + ","
                        + textColumn("d.description", 2048)
                        + ","
                        + textColumn("d.lifecycle", 16)
                        + " FROM evolution_graph_skills m LEFT JOIN"
                        + " evolution_skill_definitions d ON d.skill_id=m.skill_id AND"
                        + " d.definition_version=m.definition_version AND d.published=1"
                        + " WHERE m.graph_id=? AND m.graph_version=? ORDER BY m.skill_id LIMIT 33";
        try (var s = c.prepareStatement(query)) {
            bindGraph(s);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (definitions.size() == MAX_NODES) invalid("LIMIT_EXCEEDED");
                    String id = checkedText(r.getString(1), 128, false);
                    long version = r.getLong(2);
                    if (!"integer".equals(r.getString(3))
                            || version < 1
                            || version > Integer.MAX_VALUE) invalid("GRAPH_INVALID");
                    var ref = new SkillRef(id, (int) version);
                    String domain = checkedText(r.getString(4), 128, false);
                    String name = checkedText(r.getString(5), 160, false);
                    String description = checkedText(r.getString(6), 2048, true);
                    var lifecycle = SkillLifecycle.valueOf(checkedText(r.getString(7), 16, false));
                    List<String> behaviors = behaviors(c, ref);
                    totalText +=
                            id.length() + domain.length() + name.length() + description.length();
                    for (String b : behaviors) totalText += b.length();
                    if (totalText > MAX_GRAPH_TEXT) invalid("LIMIT_EXCEEDED");
                    definitions.add(
                            new SkillDefinition(
                                    ref, domain, name, description, behaviors, lifecycle));
                }
            }
        }
        List<SkillPrerequisite> edges = new ArrayList<>();
        try (var s =
                c.prepareStatement(
                        "SELECT "
                                + textColumn("prerequisite_skill_id", 128)
                                + ",prerequisite_version,typeof(prerequisite_version),"
                                + textColumn("dependent_skill_id", 128)
                                + ",dependent_version,typeof(dependent_version) FROM"
                                + " evolution_skill_prerequisites WHERE graph_id=? AND"
                                + " graph_version=? LIMIT 129")) {
            bindGraph(s);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (edges.size() == MAX_EDGES) invalid("LIMIT_EXCEEDED");
                    edges.add(new SkillPrerequisite(rowRef(r, 1), rowRef(r, 4)));
                }
            }
        }
        return new SkillGraph(GRAPH, definitions, edges);
    }

    private static List<String> behaviors(Connection c, SkillRef skill) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var s =
                c.prepareStatement(
                        "SELECT position,typeof(position),"
                                + textColumn("behavior", 1024)
                                + " FROM evolution_skill_behaviors WHERE skill_id=? AND"
                                + " definition_version=? ORDER BY position LIMIT 17")) {
            s.setString(1, skill.skillId());
            s.setInt(2, skill.definitionVersion());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (result.size() == MAX_BEHAVIORS) invalid("LIMIT_EXCEEDED");
                    if (!"integer".equals(r.getString(2)) || r.getLong(1) != result.size())
                        invalid("GRAPH_INVALID");
                    result.add(checkedText(r.getString(3), 1024, true));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Estimate estimate(
            Connection c,
            SkillGraph graph,
            SkillDefinition definition,
            Instant capturedAt,
            long[] budget)
            throws SQLException {
        try {
            // Latest means publication sequence, never lexicographic UTC text. Do not skip an
            // invalid newest row.
            try (var s =
                    c.prepareStatement(
                            "SELECT revision,typeof(revision),skill_version,"
                                    + "typeof(skill_version),"
                                    + textColumn("input_sha", 64)
                                    + ","
                                    + textColumn("policy", 128)
                                    + ","
                                    + textColumn("epoch_id", 128)
                                    + ","
                                    + textColumn("as_of", 64)
                                    + ",length(inputs),typeof(inputs),typeof(graph_version) FROM"
                                    + " evolution_estimate_revisions WHERE graph_id=? AND"
                                    + " graph_version=? AND skill_id=? ORDER BY revision DESC LIMIT"
                                    + " 1")) {
                bindGraph(s);
                s.setString(3, definition.ref().skillId());
                try (var r = s.executeQuery()) {
                    if (!r.next()) return Estimate.absent("missing", null);
                    long revision = r.getLong(1), version = r.getLong(3), size = r.getLong(9);
                    if (!"integer".equals(r.getString(2))
                            || revision < 1
                            || revision > 9007199254740991L
                            || !"integer".equals(r.getString(4))
                            || !"integer".equals(r.getString(11))) invalid("REVISION_INVALID");
                    if (version != definition.ref().definitionVersion()) invalid("GRAPH_MISMATCH");
                    String sha = r.getString(5),
                            policy = r.getString(6),
                            epoch = r.getString(7),
                            asOf = r.getString(8);
                    if (sha == null
                            || !sha.matches("[0-9a-f]{64}")
                            || epoch == null
                            || asOf == null) invalid("REVISION_INVALID");
                    if (!OrdinalMasteryPolicy.VERSION.equals(policy)) invalid("POLICY_UNAVAILABLE");
                    if (!"blob".equals(r.getString(10)) || size < 1) invalid("REVISION_INVALID");
                    if (size > MAX_INPUT_BYTES || budget[0] + size > MAX_TOTAL_INPUT_BYTES)
                        invalid("LIMIT_EXCEEDED");
                    budget[0] += size;
                    byte[] bytes;
                    try (var blob =
                            c.prepareStatement(
                                    "SELECT inputs FROM evolution_estimate_revisions WHERE"
                                        + " revision=? AND typeof(inputs)='blob' AND"
                                        + " length(inputs)<=? LIMIT 2")) {
                        blob.setLong(1, revision);
                        blob.setInt(2, MAX_INPUT_BYTES);
                        try (var data = blob.executeQuery()) {
                            if (!data.next()) invalid("REVISION_INVALID");
                            bytes = data.getBytes(1);
                            if (data.next() || bytes.length != size) invalid("REVISION_INVALID");
                        }
                    }
                    if (!CatalogCodec.digest(bytes).equals(sha)) invalid("REVISION_INVALID");
                    EstimateInput input = EstimateCodec.decode(bytes);
                    if (!input.graph().equals(graph.ref()) || !input.skill().equals(definition))
                        invalid("GRAPH_MISMATCH");
                    if (!input.epoch().id().equals(epoch)
                            || !input.asOf().toString().equals(asOf)
                            || input.asOf().isAfter(capturedAt)) invalid("REVISION_INVALID");
                    validateInput(input);
                    MasteryEstimate result = new OrdinalMasteryPolicy().estimate(input);
                    return new Estimate(
                            "historical",
                            null,
                            revision,
                            result.asOf(),
                            policy,
                            epoch,
                            result.band().name(),
                            result.historicalBand().name(),
                            result.support().name(),
                            result.freshness().name(),
                            result.contradicted(),
                            result.regressionCap(),
                            result.independentFamilies(),
                            result.supportedBehaviors().stream().sorted().toList(),
                            result.lastMeaningfulObservation(),
                            result.contributions().size(),
                            result.exclusions().size(),
                            counts(result.provenanceCounts()),
                            counts(result.assistanceCounts()),
                            counts(result.exposureCounts()),
                            result.reasons().stream()
                                    .map(x -> x.code().name())
                                    .distinct()
                                    .sorted()
                                    .toList(),
                            warnings(c, input));
                }
            }
        } catch (Unavailable e) {
            return Estimate.absent("unavailable", e.code);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Estimate.absent("unavailable", "REVISION_INVALID");
        }
    }

    static void validateInput(EstimateInput input) {
        if (input.observations().size() > MAX_HISTORY
                || input.activities().size() > MAX_HISTORY
                || input.mappings().size() > 64
                || input.unavailableMappings().size() > 64) invalid("LIMIT_EXCEEDED");
        checkedRevisionText(input.epoch().id(), 128);
        int totalChecks = 0, totalTargets = 0;
        for (var m : input.mappings()) checkedRevisionText(m.mappingId(), 128);
        for (var o : input.observations()) {
            if (o.sequence() < 1
                    || o.firstSequence() < 1
                    || o.firstSequence() > o.sequence()
                    || !o.id().equals(EvidenceIds.id(o.facts().source()))
                    || !input.mappings().contains(o.mapping())) invalid("REVISION_INVALID");
            if (o.context() != null) {
                if (o.context().checks().size() > 128) invalid("LIMIT_EXCEEDED");
                totalChecks += o.context().checks().size();
                for (var check : o.context().checks()) {
                    if (check.targets().size() > 128) invalid("LIMIT_EXCEEDED");
                    totalTargets += check.targets().size();
                }
            }
        }
        if (totalChecks > 4096 || totalTargets > 8192) invalid("LIMIT_EXCEEDED");
        for (var a : input.activities())
            if (a.behaviors().size() > MAX_BEHAVIORS) invalid("LIMIT_EXCEEDED");
    }

    static List<String> warnings(Connection c, EstimateInput input) throws SQLException {
        var warnings = new TreeSet<String>();
        // These headers disclose a known lifecycle change, not validation of source facts or
        // current support.
        try (var s =
                c.prepareStatement(
                        "SELECT "
                                + textColumn("o.root_id", 64)
                                + ","
                                + textColumn("x.head_id", 64)
                                + ","
                                + textColumn("h.change_kind", 32)
                                + " FROM evolution_evidence_observations o LEFT JOIN"
                                + " evolution_evidence_sources x ON x.root_id=o.root_id LEFT JOIN"
                                + " evolution_evidence_observations h ON h.evidence_id=x.head_id"
                                + " WHERE o.evidence_id=? LIMIT 2")) {
            for (var o : input.observations()) {
                if (o.facts().availability() == EvidenceTypes.Availability.RETRACTED)
                    warnings.add("EVIDENCE_WITHDRAWN");
                s.setString(1, o.id());
                try (var r = s.executeQuery()) {
                    if (!r.next()) warnings.add("EVIDENCE_MISSING");
                    else {
                        if (!EvidenceIds.root(o.facts().source()).equals(r.getString(1))
                                || r.getString(2) == null
                                || r.getString(3) == null) warnings.add("EVIDENCE_MISSING");
                        else if ("RETRACTION".equals(r.getString(3)))
                            warnings.add("EVIDENCE_WITHDRAWN");
                        else if (!o.id().equals(r.getString(2))) warnings.add("EVIDENCE_REVISED");
                        if (r.next()) invalid("REVISION_INVALID");
                    }
                }
            }
        }
        try (var s =
                c.prepareStatement(
                        "SELECT "
                                + textColumn("graph_id", 128)
                                + ",graph_version,typeof(graph_version),mapping_version,typeof(mapping_version)"
                                + " FROM evolution_evidence_mappings WHERE mapping_id=? AND"
                                + " published=1 ORDER BY mapping_version DESC LIMIT 1")) {
            for (var m : input.mappings()) {
                s.setString(1, m.mappingId());
                try (var r = s.executeQuery()) {
                    if (!r.next()
                            || !input.graph().graphId().equals(r.getString(1))
                            || r.getLong(2) != input.graph().graphVersion()
                            || !"integer".equals(r.getString(3))
                            || r.getLong(4) != m.version()
                            || !"integer".equals(r.getString(5))) warnings.add("MAPPING_CHANGED");
                }
            }
        }
        try (var s =
                c.prepareStatement(
                        "SELECT "
                                + textColumn("id", 128)
                                + " FROM evolution_estimate_epochs WHERE graph_id=? AND"
                                + " graph_version=? ORDER BY sequence DESC LIMIT 1")) {
            bindGraph(s);
            try (var r = s.executeQuery()) {
                String epoch = r.next() ? r.getString(1) : "initial";
                if (!input.epoch().id().equals(epoch)) warnings.add("EPOCH_CHANGED");
            }
        }
        return List.copyOf(warnings);
    }

    private static <E extends Enum<E>> List<Count> counts(Map<E, Integer> map) {
        return map.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .map(e -> new Count(e.getKey().name(), e.getValue()))
                .toList();
    }

    private static Ref ref(SkillRef r) {
        return new Ref(r.skillId(), r.definitionVersion());
    }

    private static SkillRef rowRef(ResultSet r, int at) throws SQLException {
        String id = checkedText(r.getString(at), 128, false);
        long version = r.getLong(at + 1);
        if (!"integer".equals(r.getString(at + 2)) || version < 1 || version > Integer.MAX_VALUE)
            invalid("GRAPH_INVALID");
        return new SkillRef(id, (int) version);
    }

    private static void bindGraph(PreparedStatement s) throws SQLException {
        s.setString(1, GRAPH.graphId());
        s.setInt(2, GRAPH.graphVersion());
    }

    private static String textColumn(String column, int max) {
        // SQLite's UTF-8 byte bound also prevents huge TEXT/BLOB reads before Java validation.
        return "CASE WHEN typeof("
                + column
                + ")='text' AND length(CAST("
                + column
                + " AS BLOB)) BETWEEN 1 AND "
                + (max * 4)
                + " THEN "
                + column
                + " END";
    }

    private static String checkedText(String value, int max, boolean multiline) {
        if (value == null || value.isBlank() || value.length() > max) invalid("GRAPH_INVALID");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)
                    && !(multiline && (ch == '\n' || ch == '\r' || ch == '\t')))
                invalid("GRAPH_INVALID");
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    invalid("GRAPH_INVALID");
            } else if (Character.isLowSurrogate(ch)) invalid("GRAPH_INVALID");
        }
        return value;
    }

    private static void checkedRevisionText(String value, int max) {
        try {
            checkedText(value, max, false);
        } catch (Unavailable e) {
            invalid("REVISION_INVALID");
        }
    }

    private static void invalid(String code) {
        throw new Unavailable(code);
    }
}
