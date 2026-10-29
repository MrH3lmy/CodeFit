package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.*;
import com.codefit.repository.EvidenceRepository;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Historical metadata on a single query-only snapshot. No estimation publication or source
 * execution.
 */
final class PracticeAnalytics {
    static final int REVISIONS = 16, EVENTS = 32;

    record Revision(
            long id,
            String skill,
            int version,
            int graphVersion,
            String policy,
            String asOf,
            String epoch,
            String status,
            MasteryEstimate estimate,
            List<String> warnings) {}

    record Event(
            long sequence,
            String id,
            String root,
            int revision,
            String recordedAt,
            String observedAt,
            String change,
            String provenance,
            String outcome,
            String assistance,
            String exposure,
            String availability,
            boolean head,
            List<String> skills) {}

    record View(
            String capturedAt,
            String snapshot,
            int page,
            boolean more,
            SkillGraph graph,
            List<Revision> revisions,
            List<Event> events) {}

    static View read(ExperimentalProfile profile, Clock clock, int page, String expected)
            throws SQLException {
        if (!profile.isLearning()
                || page < 0
                || page > 63
                || (page > 0 && !expected.matches("[0-9a-f]{64}"))
                || (page == 0 && !expected.isEmpty()))
            throw new IllegalArgumentException("INVALID_INPUT");
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            var graph = LearnerSkillProjection.graph(c);
            if (!graph.equals(JavaDebuggingSkillSeed.graph()))
                throw new SQLException("PROFILE_INVALID");
            String fingerprint = fingerprint(c);
            if (page > 0 && !expected.equals(fingerprint))
                throw new SQLException("SNAPSHOT_CHANGED");
            Instant now = clock.instant();
            var revisions = new ArrayList<Revision>();
            try (var q =
                    c.prepareStatement(
                            "SELECT"
                                + " revision,skill_id,skill_version,graph_version,policy,as_of,epoch_id,typeof(skill_version),typeof(graph_version)"
                                + " FROM evolution_estimate_revisions WHERE graph_id=? ORDER BY"
                                + " revision DESC LIMIT ? OFFSET ?")) {
                q.setString(1, graph.ref().graphId());
                q.setInt(2, REVISIONS + 1);
                q.setInt(3, page * REVISIONS);
                try (var r = q.executeQuery()) {
                    while (r.next()) {
                        long id = r.getLong(1);
                        String skill = label(r.getString(2)),
                                policy = label(r.getString(5)),
                                epoch = label(r.getString(7));
                        int version = r.getInt(3), gv = r.getInt(4);
                        String at = time(r.getString(6), now);
                        var definition =
                                graph.definitions().stream()
                                        .filter(
                                                d ->
                                                        d.ref().skillId().equals(skill)
                                                                && d.ref().definitionVersion()
                                                                        == version)
                                        .findFirst();
                        if (id <= 0
                                || version < 1
                                || gv < 1
                                || !r.getString(8).equals("integer")
                                || !r.getString(9).equals("integer"))
                            throw new SQLException("PROFILE_INVALID");
                        if (gv != 1
                                || !policy.equals(OrdinalMasteryPolicy.VERSION)
                                || definition.isEmpty()) {
                            revisions.add(
                                    new Revision(
                                            id,
                                            skill,
                                            version,
                                            gv,
                                            policy,
                                            at,
                                            epoch,
                                            "INCOMPATIBLE",
                                            null,
                                            List.of("VERSION_UNAVAILABLE")));
                            continue;
                        }
                        try {
                            var saved = new EstimateRevisionRepository().find(c, id).orElseThrow();
                            var input = input(c, id);
                            LearnerSkillProjection.validateInput(input);
                            if (!input.skill().equals(definition.get()))
                                throw new IllegalStateException();
                            revisions.add(
                                    new Revision(
                                            id,
                                            skill,
                                            version,
                                            gv,
                                            policy,
                                            at,
                                            epoch,
                                            "HISTORICAL",
                                            saved.estimate(),
                                            LearnerSkillProjection.warnings(c, input)));
                        } catch (IllegalArgumentException | IllegalStateException failure) {
                            revisions.add(
                                    new Revision(
                                            id,
                                            skill,
                                            version,
                                            gv,
                                            policy,
                                            at,
                                            epoch,
                                            "UNAVAILABLE",
                                            null,
                                            List.of("REVISION_INVALID")));
                        }
                    }
                }
            }
            var events = new ArrayList<Event>();
            try (var q =
                    c.prepareStatement(
                            "SELECT evidence_id,root_id,revision,change_kind,previous_id FROM"
                                + " evolution_evidence_observations ORDER BY sequence DESC LIMIT ?"
                                + " OFFSET ?")) {
                q.setInt(1, EVENTS + 1);
                q.setInt(2, page * EVENTS);
                try (var r = q.executeQuery()) {
                    while (r.next()) {
                        var stored = new EvidenceRepository().find(c, r.getString(1)).orElseThrow();
                        var o = stored.observation();
                        String root = EvidenceIds.root(o.source());
                        if (!root.equals(r.getString(2))
                                || o.source().revision() != r.getInt(3)
                                || !o.change().name().equals(r.getString(4))
                                || !Objects.equals(o.previousEvidenceId(), r.getString(5)))
                            throw new SQLException("PROFILE_INVALID");
                        String recorded = time(stored.recordedAt().toString(), now);
                        var head = new EvidenceRepository().head(c, root).orElseThrow();
                        if (!EvidenceIds.root(head.observation().source()).equals(root))
                            throw new SQLException("PROFILE_INVALID");
                        var skills = new TreeSet<String>();
                        try (var m =
                                c.prepareStatement(
                                        "SELECT DISTINCT r.skill_id,r.definition_version FROM"
                                                + " evolution_evidence_interpretations i JOIN"
                                                + " evolution_evidence_mapping_rules r ON"
                                                + " r.mapping_id=i.mapping_id AND"
                                                + " r.mapping_version=i.mapping_version AND"
                                                + " r.selector=i.selector JOIN"
                                                + " evolution_evidence_mappings m ON"
                                                + " m.mapping_id=i.mapping_id AND"
                                                + " m.mapping_version=i.mapping_version WHERE"
                                                + " i.evidence_id=? AND m.graph_id=? AND"
                                                + " m.graph_version=1 AND m.published=1 AND"
                                                + " r.source_type=? LIMIT 5")) {
                            m.setString(1, stored.id());
                            m.setString(2, graph.ref().graphId());
                            m.setString(3, o.source().type().name());
                            try (var rows = m.executeQuery()) {
                                while (rows.next()) {
                                    String skill = rows.getString(1);
                                    int v = rows.getInt(2);
                                    if (graph.definitions().stream()
                                            .noneMatch(d -> d.ref().equals(new SkillRef(skill, v))))
                                        throw new SQLException("PROFILE_INVALID");
                                    skills.add(skill);
                                }
                            }
                        }
                        events.add(
                                new Event(
                                        stored.sequence(),
                                        stored.id(),
                                        root,
                                        o.source().revision(),
                                        recorded,
                                        o.time().observedAt() == null
                                                ? null
                                                : time(o.time().observedAt().toString(), now),
                                        o.change().name(),
                                        o.provenance().name(),
                                        o.outcome().name(),
                                        o.assistance().name(),
                                        o.exposure().name(),
                                        o.availability().name(),
                                        head.id().equals(stored.id()),
                                        List.copyOf(skills)));
                    }
                }
            }
            boolean more = revisions.size() > REVISIONS || events.size() > EVENTS;
            var result =
                    new View(
                            now.toString(),
                            fingerprint,
                            page,
                            more,
                            graph,
                            List.copyOf(
                                    revisions.subList(0, Math.min(REVISIONS, revisions.size()))),
                            List.copyOf(events.subList(0, Math.min(EVENTS, events.size()))));
            c.commit();
            return result;
        }
    }

    private static EstimateInput input(Connection c, long id) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT inputs FROM evolution_estimate_revisions WHERE revision=?")) {
            q.setLong(1, id);
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new SQLException("PROFILE_INVALID");
                return EstimateCodec.decode(r.getBytes(1));
            }
        }
    }

    private static String label(String s) throws SQLException {
        if (s == null || !s.matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}"))
            throw new SQLException("PROFILE_INVALID");
        return s;
    }

    private static String time(String s, Instant now) throws SQLException {
        try {
            var t = Instant.parse(s);
            if (t.isAfter(now)) throw new IllegalArgumentException();
            return t.toString();
        } catch (RuntimeException e) {
            throw new SQLException("PROFILE_INVALID");
        }
    }

    /** Bounded content digest fences pages against corrections, resets and mapping changes. */
    private static String fingerprint(Connection c) throws SQLException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            for (String table :
                    List.of(
                            "evolution_estimate_revisions",
                            "evolution_estimate_epochs",
                            "evolution_evidence_observations",
                            "evolution_evidence_sources",
                            "evolution_evidence_interpretations",
                            "evolution_evidence_mappings",
                            "evolution_evidence_mapping_rules")) {
                digest.update(table.getBytes(StandardCharsets.UTF_8));
                try (var q = c.createStatement();
                        var r =
                                q.executeQuery(
                                        "SELECT COUNT(*) FROM (SELECT 1 FROM "
                                                + table
                                                + " LIMIT 2049)")) {
                    r.next();
                    if (r.getInt(1) > (table.equals("evolution_estimate_revisions") ? 1024 : 2048))
                        throw new SQLException("INPUT_LIMIT");
                }
                try (var q = c.createStatement();
                        var empty = q.executeQuery("SELECT * FROM " + table + " WHERE 0")) {
                    var meta = empty.getMetaData();
                    if (meta.getColumnCount() > 32) throw new SQLException("INPUT_LIMIT");
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String column = "\"" + meta.getColumnName(i).replace("\"", "\"\"") + "\"";
                        try (var size = c.createStatement();
                                var r =
                                        size.executeQuery(
                                                "SELECT COALESCE(MAX(length(CAST("
                                                        + column
                                                        + " AS BLOB))),0),COALESCE(SUM(length(CAST("
                                                        + column
                                                        + " AS BLOB))),0) FROM "
                                                        + table)) {
                            r.next();
                            if (r.getLong(1) > 1048576 || r.getLong(2) > 16777216)
                                throw new SQLException("INPUT_LIMIT");
                        }
                    }
                }
                try (var q = c.createStatement();
                        var r =
                                q.executeQuery(
                                        "SELECT * FROM " + table + " ORDER BY rowid LIMIT 2049")) {
                    int rows = 0;
                    while (r.next()) {
                        if (++rows > 2048) throw new SQLException("INPUT_LIMIT");
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                            // Column sizes were guarded in SQLite on this same read snapshot.
                            byte[] value = r.getBytes(i);
                            if (value == null) {
                                digest.update((byte) 0);
                                continue;
                            }
                            if (value.length > 1048576 || (total += value.length) > 16777216)
                                throw new SQLException("INPUT_LIMIT");
                            digest.update((byte) 1);
                            digest.update(
                                    java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
                            digest.update(value);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String coverage(MasteryEstimate e, int b) {
        if (e.contradicted()
                && e.contributions().stream()
                        .anyMatch(
                                x ->
                                        x.eligibleDeliberateFailure()
                                                && x.failedBehaviors().contains(b)))
            return "CONTRADICTED";
        if (e.supportedBehaviors().contains(b)) return "SUPPORTED";
        if (e.contributions().isEmpty())
            return e.exclusions().isEmpty() ? "NOT_EVALUATED" : "EXCLUDED";
        return "UNSUPPORTED";
    }

    static void write(JsonGenerator g, View v) throws IOException {
        g.writeStringField("capturedAt", v.capturedAt());
        g.writeStringField("snapshot", v.snapshot());
        g.writeNumberField("page", v.page());
        g.writeBooleanField("more", v.more());
        g.writeStringField("graphId", v.graph().ref().graphId());
        g.writeNumberField("graphVersion", 1);
        g.writeArrayFieldStart("skills");
        for (var d : v.graph().topologicalOrder()) {
            g.writeStartObject();
            g.writeStringField("id", d.ref().skillId());
            g.writeNumberField("version", d.ref().definitionVersion());
            g.writeStringField("name", d.name());
            g.writeArrayFieldStart("behaviors");
            for (String b : d.observableBehaviors()) g.writeString(b);
            g.writeEndArray();
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeArrayFieldStart("edges");
        for (var edge : v.graph().prerequisites()) {
            g.writeStartObject();
            g.writeStringField("from", edge.prerequisite().skillId());
            g.writeStringField("to", edge.dependent().skillId());
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeArrayFieldStart("revisions");
        for (var r : v.revisions()) {
            g.writeStartObject();
            g.writeNumberField("id", r.id());
            g.writeStringField("skill", r.skill());
            g.writeNumberField("version", r.version());
            g.writeNumberField("graphVersion", r.graphVersion());
            g.writeStringField("policy", r.policy());
            g.writeStringField("asOf", r.asOf());
            g.writeStringField("epoch", r.epoch());
            g.writeStringField("status", r.status());
            strings(g, "warnings", r.warnings());
            g.writeFieldName("interpretation");
            if (r.estimate() == null) g.writeNull();
            else {
                var e = r.estimate();
                g.writeStartObject();
                g.writeStringField("band", e.band().name());
                g.writeStringField("support", e.support().name());
                g.writeStringField("freshness", e.freshness().name());
                g.writeBooleanField("contradicted", e.contradicted());
                g.writeNumberField("families", e.independentFamilies());
                strings(
                        g,
                        "coverage",
                        v.graph().definitions().stream()
                                .filter(d -> d.ref().skillId().equals(r.skill()))
                                .findFirst()
                                .orElseThrow()
                                .observableBehaviors()
                                .stream()
                                .map(b -> coverage(e, 0))
                                .toList());
                strings(
                        g,
                        "reasons",
                        e.reasons().stream().map(x -> x.code().name()).distinct().toList());
                strings(
                        g,
                        "explanations",
                        e.reasons().stream().map(x -> x.message()).distinct().toList());
                strings(
                        g,
                        "exclusions",
                        e.exclusions().stream().map(x -> x.code().name()).distinct().toList());
                var sources =
                        e.contributions().stream()
                                .map(x -> x.evidenceId())
                                .distinct()
                                .sorted()
                                .toList();
                strings(g, "evidence", sources.stream().limit(64).toList());
                g.writeBooleanField("evidenceTruncated", sources.size() > 64);
                g.writeEndObject();
            }
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeArrayFieldStart("events");
        for (var e : v.events()) {
            g.writeStartObject();
            g.writeNumberField("sequence", e.sequence());
            g.writeStringField("id", e.id());
            g.writeStringField("root", e.root());
            g.writeNumberField("revision", e.revision());
            g.writeStringField("recordedAt", e.recordedAt());
            if (e.observedAt() == null) g.writeNullField("observedAt");
            else g.writeStringField("observedAt", e.observedAt());
            g.writeStringField("change", e.change());
            g.writeStringField("provenance", e.provenance());
            g.writeStringField("outcome", e.outcome());
            g.writeStringField("assistance", e.assistance());
            g.writeStringField("exposure", e.exposure());
            g.writeStringField("availability", e.availability());
            g.writeBooleanField("head", e.head());
            strings(g, "skills", e.skills());
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    private static void strings(JsonGenerator g, String key, List<String> values)
            throws IOException {
        g.writeArrayFieldStart(key);
        for (String s : values) g.writeString(s);
        g.writeEndArray();
    }
}
