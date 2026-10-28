package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.CatalogCodec;
import com.codefit.repository.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit estimates only. Never calls overview, recommendations, rewards or the evaluator. */
final class PracticeSkillsService {
    record Item(
            String skillId,
            int skillVersion,
            String name,
            long revision,
            String inputSha,
            boolean created,
            MasteryEstimate estimate) {}

    record Result(
            String commandId,
            String originSessionId,
            String state,
            String reason,
            String asOf,
            String policy,
            String inputSha,
            List<Item> skills) {}

    enum Boundary {
        ACCEPTED,
        CAPTURED,
        FIRST_PUBLISHED,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    private final ExperimentalProfile profile;
    private final Clock clock;
    private final java.util.function.Consumer<Boundary> hook;

    PracticeSkillsService(ExperimentalProfile profile, Clock clock) {
        this(profile, clock, b -> {});
    }

    PracticeSkillsService(
            ExperimentalProfile profile, Clock clock, java.util.function.Consumer<Boundary> hook) {
        if (!profile.isLearning()) throw new IllegalArgumentException("NOT_AUTHORIZED");
        this.profile = profile;
        this.clock = clock;
        this.hook = hook;
    }

    synchronized Result reconcile(String id, String origin) throws SQLException {
        identity(id, origin);
        var old = status(id, origin);
        if (old != null) return old;
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            lock(c);
            try (var q =
                    c.prepareStatement("SELECT 1 FROM desktop_skill_commands WHERE command_id=?")) {
                q.setString(1, id);
                try (var r = q.executeQuery()) {
                    if (r.next()) {
                        c.rollback();
                        return status(id, origin);
                    }
                }
            }
            Instant at = clock.instant();
            try (var q =
                    c.prepareStatement("INSERT INTO desktop_skill_commands VALUES(?,?,?,?,?)")) {
                q.setString(1, id);
                q.setString(2, origin);
                q.setString(3, profile.id());
                q.setString(4, at.toString());
                q.setString(5, OrdinalMasteryPolicy.VERSION);
                q.executeUpdate();
            }
            c.commit();
            c.setAutoCommit(true);
            hook.accept(Boundary.ACCEPTED);
            var engine = new MasteryEstimateService();
            try {
                long version = version(c);
                c.setAutoCommit(false);
                preflight(c, at);
                var captured = engine.capturePilot(c, at);
                for (var in : captured.inputs()) {
                    if (!in.unavailableMappings().isEmpty())
                        throw new IllegalStateException("Mappings unavailable");
                    for (var ref : in.mappings())
                        try (var q =
                                c.prepareStatement(
                                        "SELECT 1 FROM evolution_evidence_mappings WHERE"
                                            + " mapping_id=? AND mapping_version>? AND published=1"
                                            + " LIMIT 1")) {
                            q.setString(1, ref.mappingId());
                            q.setInt(2, ref.version());
                            try (var r = q.executeQuery()) {
                                if (r.next()) throw new IllegalStateException("Mapping superseded");
                            }
                        }
                    if (EstimateCodec.encode(in).length > 1024 * 1024)
                        throw new IllegalStateException("Input limit");
                }
                c.commit();
                c.setAutoCommit(true);
                hook.accept(Boundary.CAPTURED);
                var prepared = engine.prepare(captured);
                c.setAutoCommit(false);
                lock(c);
                if (version(c) != version) {
                    c.rollback();
                    c.setAutoCommit(true);
                    reject(c, id, "SOURCE_CHANGED");
                    return status(id, origin);
                }
                var items = new ArrayList<Item>();
                for (var item : prepared) {
                    String sha = CatalogCodec.digest(EstimateCodec.encode(item.input()));
                    boolean exists;
                    try (var q =
                            c.prepareStatement(
                                    "SELECT 1 FROM evolution_estimate_revisions WHERE input_sha=?"
                                        + " AND policy=?")) {
                        q.setString(1, sha);
                        q.setString(2, OrdinalMasteryPolicy.VERSION);
                        try (var r = q.executeQuery()) {
                            exists = r.next();
                        }
                    }
                    var revision = engine.publish(c, item);
                    try (var q =
                            c.prepareStatement(
                                    "INSERT INTO desktop_skill_results VALUES(?,?,?,?)")) {
                        q.setString(1, id);
                        q.setString(2, item.input().skill().ref().skillId());
                        q.setLong(3, revision.id());
                        q.setInt(4, exists ? 0 : 1);
                        q.executeUpdate();
                    }
                    items.add(
                            new Item(
                                    item.input().skill().ref().skillId(),
                                    item.input().skill().ref().definitionVersion(),
                                    item.input().skill().name(),
                                    revision.id(),
                                    revision.inputSha(),
                                    !exists,
                                    revision.estimate()));
                    if (items.size() == 1) hook.accept(Boundary.FIRST_PUBLISHED);
                }
                receipt(c, id, "PUBLISHED", "SAVED", digest(items));
                hook.accept(Boundary.BEFORE_COMMIT);
                c.commit();
                c.setAutoCommit(true);
                hook.accept(Boundary.AFTER_COMMIT);
                return new Result(
                        id,
                        origin,
                        "PUBLISHED",
                        "SAVED",
                        at.toString(),
                        OrdinalMasteryPolicy.VERSION,
                        digest(items),
                        List.copyOf(items));
            } catch (SQLException | RuntimeException failure) {
                if (!c.getAutoCommit()) {
                    c.rollback();
                    c.setAutoCommit(true);
                }
                // If a reply was lost after commit, the immutable receipt wins.
                var committed = status(id, origin);
                if (committed != null && committed.state().equals("PUBLISHED")) throw failure;
                reject(c, id, "INPUT_UNAVAILABLE");
                return status(id, origin);
            }
        }
    }

    synchronized Result status(String id, String origin) throws SQLException {
        identity(id, origin);
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            var r = read(c, id, origin);
            c.commit();
            return r;
        }
    }

    synchronized Result latest() throws SQLException {
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            Result result = null;
            try (var q = c.createStatement();
                    var r =
                            q.executeQuery(
                                    "SELECT command_id,origin_session FROM desktop_skill_commands"
                                        + " ORDER BY rowid DESC LIMIT 1")) {
                if (r.next()) result = read(c, r.getString(1), r.getString(2));
            }
            c.commit();
            return result;
        }
    }

    private Result read(Connection c, String id, String origin) throws SQLException {
        String at, policy;
        try (var q =
                c.prepareStatement(
                        "SELECT origin_session,profile_id,as_of,policy FROM desktop_skill_commands"
                            + " WHERE command_id=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                if (!origin.equals(r.getString(1)) || !profile.id().equals(r.getString(2)))
                    throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                at = r.getString(3);
                policy = r.getString(4);
                if (!policy.equals(OrdinalMasteryPolicy.VERSION)
                        || Instant.parse(at).isAfter(clock.instant()))
                    throw new SQLException("PROFILE_INVALID");
            }
        }
        String state = "INTERRUPTED", reason = "NOT_PUBLISHED", sha = "";
        try (var q =
                c.prepareStatement(
                        "SELECT state,reason,input_sha FROM desktop_skill_receipts WHERE"
                            + " command_id=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
                if (r.next()) {
                    state = r.getString(1);
                    reason = r.getString(2);
                    sha = r.getString(3);
                }
            }
        }
        var items = new ArrayList<Item>();
        try (var q =
                c.prepareStatement(
                        "SELECT skill_id,revision,created FROM desktop_skill_results WHERE"
                            + " command_id=? ORDER BY skill_id LIMIT 5")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    var definition =
                            JavaDebuggingSkillSeed.graph().definitions().stream()
                                    .filter(
                                            d -> {
                                                try {
                                                    return d.ref().skillId().equals(r.getString(1));
                                                } catch (SQLException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            })
                                    .findFirst()
                                    .orElseThrow();
                    var revision =
                            new EstimateRevisionRepository().find(c, r.getLong(2)).orElseThrow();
                    var e = revision.estimate();
                    if (!e.graph().equals(JavaDebuggingSkillSeed.GRAPH)
                            || !e.skill().equals(definition.ref())
                            || !e.asOf().toString().equals(at))
                        throw new SQLException("PROFILE_INVALID");
                    items.add(
                            new Item(
                                    definition.ref().skillId(),
                                    definition.ref().definitionVersion(),
                                    definition.name(),
                                    revision.id(),
                                    revision.inputSha(),
                                    r.getInt(3) == 1,
                                    e));
                }
            }
        }
        if (state.equals("PUBLISHED")
                ? items.size() != 4 || !digest(items).equals(sha)
                : !items.isEmpty()) throw new SQLException("PROFILE_INVALID");
        return new Result(id, origin, state, reason, at, policy, sha, List.copyOf(items));
    }

    private void reject(Connection c, String id, String reason) throws SQLException {
        c.setAutoCommit(false);
        try {
            lock(c);
            receipt(c, id, "REJECTED", reason, "");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static void receipt(Connection c, String id, String state, String reason, String sha)
            throws SQLException {
        try (var q = c.prepareStatement("INSERT INTO desktop_skill_receipts VALUES(?,?,?,?)")) {
            q.setString(1, id);
            q.setString(2, state);
            q.setString(3, reason);
            q.setString(4, sha);
            q.executeUpdate();
        }
    }

    private static String digest(List<Item> items) {
        return CatalogCodec.digest(
                items.stream()
                        .sorted(Comparator.comparing(Item::skillId))
                        .map(i -> i.skillId() + ":" + i.inputSha())
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void identity(String id, String origin) {
        if (!ApplicationProtocol.uuid(id) || !ApplicationProtocol.uuid(origin))
            throw new IllegalArgumentException("INVALID_INPUT");
    }

    private static void lock(Connection c) throws SQLException {
        try (var q = c.createStatement()) {
            q.executeUpdate("UPDATE desktop_profile SET version=version");
        }
    }

    private static long version(Connection c) throws SQLException {
        try (var q = c.createStatement();
                var r = q.executeQuery("PRAGMA data_version")) {
            r.next();
            return r.getLong(1);
        }
    }

    private static void preflight(Connection c, Instant at) throws SQLException {
        // Bound graph strings before repository hydration. Never load an unbounded local corpus.
        try (var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT COUNT(*),COALESCE(MAX(length(CAST(name AS"
                                    + " BLOB))+length(CAST(description AS BLOB))),0) FROM"
                                    + " evolution_skill_definitions")) {
            r.next();
            if (r.getInt(1) > 32 || r.getLong(2) > 24000) throw new SQLException("INPUT_LIMIT");
        }
        try (var q = c.createStatement();
                var r = q.executeQuery("SELECT COUNT(*) FROM evolution_skill_graphs")) {
            r.next();
            if (r.getInt(1) > 32) throw new SQLException("INPUT_LIMIT");
        }
        for (String table :
                List.of(
                        "evolution_evidence_observations",
                        "evolution_evidence_interpretations",
                        "evolution_evidence_mapping_rules",
                        "evolution_challenge_presentations",
                        "evolution_challenge_exposure_events",
                        "evolution_estimate_epochs",
                        "evolution_skill_behaviors",
                        "evolution_skill_prerequisites")) {
            try (var q = c.createStatement();
                    var r =
                            q.executeQuery(
                                    "SELECT COUNT(*) FROM (SELECT 1 FROM "
                                            + table
                                            + " LIMIT 10001)")) {
                r.next();
                if (r.getInt(1) > 10000) throw new SQLException("INPUT_LIMIT");
            }
        }
        for (var entry :
                Map.of(
                                "evolution_evidence_observations",
                                "payload",
                                "evolution_submissions",
                                "snapshot",
                                "evolution_evaluation_runs",
                                "payload",
                                "evolution_evaluation_revisions",
                                "payload",
                                "evolution_challenge_definitions",
                                "payload")
                        .entrySet()) {
            try (var q = c.createStatement();
                    var r =
                            q.executeQuery(
                                    "SELECT COUNT(*),COALESCE(MAX(length("
                                            + entry.getValue()
                                            + ")),0),COALESCE(SUM(length("
                                            + entry.getValue()
                                            + ")),0) FROM "
                                            + entry.getKey())) {
                r.next();
                if (r.getLong(1) > 1000 || r.getLong(2) > 2000000 || r.getLong(3) > 16000000)
                    throw new SQLException("INPUT_LIMIT");
            }
        }
        if (!new SkillCatalogRepository()
                .findGraph(c, JavaDebuggingSkillSeed.GRAPH)
                .filter(JavaDebuggingSkillSeed.graph()::equals)
                .isPresent()) throw new SQLException("GRAPH_UNAVAILABLE");
        try (var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT 1 FROM evolution_skill_graphs WHERE"
                                    + " graph_id='java-debugging-concurrency' AND graph_version>1"
                                    + " AND published=1 LIMIT 1")) {
            if (r.next()) throw new SQLException("GRAPH_CONFLICT");
        }
        try (var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT as_of FROM desktop_skill_commands ORDER BY rowid DESC LIMIT"
                                    + " 10001")) {
            int count = 0;
            while (r.next()) {
                if (++count > 10000 || Instant.parse(r.getString(1)).isAfter(at))
                    throw new SQLException("CLOCK_CONFLICT");
            }
        }
        try (var q = c.createStatement();
                var r = q.executeQuery("SELECT started_at FROM evolution_estimate_epochs")) {
            while (r.next())
                if (Instant.parse(r.getString(1)).isAfter(at))
                    throw new SQLException("EPOCH_CONFLICT");
        }
        // An effective observation without its interpretation must never silently disappear.
        try (var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT 1 FROM evolution_evidence_sources s WHERE NOT EXISTS"
                                    + " (SELECT 1 FROM evolution_evidence_interpretations i WHERE"
                                    + " i.evidence_id=s.head_id) LIMIT 1")) {
            if (r.next()) throw new SQLException("MAPPING_UNAVAILABLE");
        }
    }
}
