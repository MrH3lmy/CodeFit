package com.codefit.repository;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.SkillRef;
import com.codefit.evolution.evidence.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Local append-only observation history with explicit interpretation and bounded explainable reads.
 */
public final class EvidenceRepository {
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * #214 must compare ALL metadata with its durable evaluation on this SAME connection. No runner
     * callback.
     */
    @FunctionalInterface
    public interface ChallengeLookup {
        boolean matches(Connection connection, EvidenceObservation observation) throws SQLException;
    }

    private final ChallengeLookup challenges;
    private final LearningEvidenceSourceRepository sources = new LearningEvidenceSourceRepository();

    public EvidenceRepository() {
        this((c, o) -> false);
    }

    public EvidenceRepository(ChallengeLookup lookup) {
        this.challenges = Objects.requireNonNull(lookup);
    }

    public <T> T transaction(SqlWork<T> work) {
        try (var c = DatabaseConfig.getConnection()) {
            try (var s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            c.setAutoCommit(false);
            try {
                // Acquire SQLite's single writer before any reads: concurrent replay cannot upgrade
                // a stale snapshot.
                try (var s = c.createStatement()) {
                    s.executeUpdate(
                            "UPDATE evolution_evidence_cursors SET last_source_id=last_source_id"
                                    + " WHERE 0");
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Local evidence transaction failed", failure);
        }
    }

    public AppendResult append(EvidenceObservation o, EvidenceMappingRef mapping, String selector) {
        return transaction(c -> append(c, o, mapping, selector));
    }

    /** Caller owns commit/rollback; roll back the entire transaction after ANY exception. */
    public AppendResult append(
            Connection c, EvidenceObservation o, EvidenceMappingRef mapping, String selector)
            throws SQLException {
        requireTransaction(c);
        if (selector == null || selector.length() > 128)
            throw new IllegalArgumentException("Bounded selector required");
        var m =
                new EvidenceMappingRepository()
                        .find(c, mapping)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown mapping version"));
        String id = EvidenceIds.id(o.source()), root = EvidenceIds.root(o.source());
        var existing = find(c, id);
        if (existing.isPresent()) {
            if (!existing.get().observation().equals(o))
                throw new IllegalArgumentException("Conflicting source-event identity");
            if (!context(c, id).equals(selector))
                throw new IllegalArgumentException(
                        "Remapping cannot rewrite source context; explicitly revise");
            // Reinterpret a known immutable observation; never alter its recordedAt or head.
            interpret(c, id, m.ref(), selector);
            return AppendResult.DUPLICATE;
        }
        var head = head(c, root);
        if (o.change() == Change.ORIGINAL) {
            if (head.isPresent())
                throw new IllegalArgumentException("Source already has an observation");
        } else {
            var previous =
                    head.orElseThrow(
                            () -> new IllegalArgumentException("Correction has no source history"));
            if (!previous.id().equals(o.previousEvidenceId())
                    || previous.observation().source().revision() + 1 != o.source().revision())
                throw new IllegalArgumentException(
                        "Correction must extend current head by one revision");
            var oldEvaluation = previous.observation().evaluation();
            var newEvaluation = o.evaluation();
            if (oldEvaluation != null
                    && (!oldEvaluation.submissionId().equals(newEvaluation.submissionId())
                            || !oldEvaluation.familyId().equals(newEvaluation.familyId())
                            || !oldEvaluation
                                    .artifactSha256()
                                    .equals(newEvaluation.artifactSha256())
                            || !Objects.equals(
                                    oldEvaluation.problemAttemptId(),
                                    newEvaluation.problemAttemptId())))
                throw new IllegalArgumentException(
                        "Corrections must preserve submission, family, artifact and linked attempt"
                                + " identity");
        }
        if (o.change() != Change.RETRACTION) validateSource(c, o, selector);
        else {
            var prior = head.orElseThrow().observation();
            if (!prior.retract(o.reason(), o.actor()).equals(o))
                throw new IllegalArgumentException(
                        "Retraction must preserve prior observation facts");
            String previousSelector = context(c, o.previousEvidenceId());
            if (previousSelector == null || !previousSelector.equals(selector))
                throw new IllegalArgumentException(
                        "Retraction must preserve its prior interpretation");
        }
        if (head.isEmpty()) {
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " evolution_evidence_sources(root_id,source_type,source_record_id)"
                                + " VALUES(?,?,?)")) {
                s.setString(1, root);
                s.setString(2, o.source().type().name());
                s.setLong(3, o.source().recordId());
                s.executeUpdate();
            }
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_evidence_observations(evidence_id,root_id,revision,recorded_at,previous_id,change_kind,payload)"
                            + " VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1, id);
            s.setString(2, root);
            s.setInt(3, o.source().revision());
            s.setString(4, Instant.now().toString());
            s.setString(5, o.previousEvidenceId());
            s.setString(6, o.change().name());
            s.setBytes(7, EvidenceCodec.encode(o));
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement(
                        "UPDATE evolution_evidence_sources SET head_id=? WHERE root_id=?")) {
            s.setString(1, id);
            s.setString(2, root);
            s.executeUpdate();
        }
        if (o.evaluation() != null && head.isEmpty()) {
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " evolution_evidence_evaluation_links(submission_id,root_id,problem_attempt_id)"
                                + " VALUES(?,?,?)")) {
                s.setString(1, o.evaluation().submissionId());
                s.setString(2, root);
                if (o.evaluation().problemAttemptId() == null) s.setNull(3, Types.BIGINT);
                else s.setLong(3, o.evaluation().problemAttemptId());
                s.executeUpdate();
            }
        }
        interpret(c, id, mapping, selector);
        return AppendResult.INSERTED;
    }

    private void validateSource(Connection c, EvidenceObservation o, String selector)
            throws SQLException {
        if (o.source().type() == SourceType.CHALLENGE_EVALUATION) {
            if (o.provenance() != Provenance.LOCAL_CHECK
                    || !o.evaluation().familyId().equals(selector)
                    || !challenges.matches(c, o))
                throw new IllegalArgumentException(
                        "Matching durable challenge evaluation required");
        } else {
            var raw =
                    sources.find(c, o.source())
                            .orElseThrow(() -> new IllegalArgumentException("Source unavailable"));
            if (!raw.observation().sameFacts(o) || !raw.selector().equals(selector))
                throw new IllegalArgumentException("Evidence must match persisted source facts");
        }
    }

    private static void interpret(Connection c, String id, EvidenceMappingRef m, String selector)
            throws SQLException {
        var previous = selector(c, id, m);
        if (previous != null) {
            if (!previous.equals(selector))
                throw new IllegalArgumentException(
                        "Conflicting interpretation; explicitly revise source");
            return;
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_evidence_interpretations VALUES(?,?,?,?)")) {
            s.setString(1, id);
            s.setString(2, m.mappingId());
            s.setInt(3, m.version());
            s.setString(4, selector);
            s.executeUpdate();
        }
    }

    /** The immutable source context of this revision, independent of mapping selection. */
    public String context(Connection c, String id) throws SQLException {
        try (var statement =
                c.prepareStatement(
                        "SELECT selector FROM evolution_evidence_interpretations WHERE"
                                + " evidence_id=? LIMIT 1")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new IllegalArgumentException("Evidence has no interpretation");
                return rows.getString(1);
            }
        }
    }

    private static String selector(Connection c, String id, EvidenceMappingRef m)
            throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT selector FROM evolution_evidence_interpretations WHERE"
                                + " evidence_id=? AND mapping_id=? AND mapping_version=?")) {
            s.setString(1, id);
            s.setString(2, m.mappingId());
            s.setInt(3, m.version());
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    public Optional<StoredEvidence> find(Connection c, String id) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT sequence,evidence_id,recorded_at,payload FROM"
                                + " evolution_evidence_observations WHERE evidence_id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public Optional<StoredEvidence> head(Connection c, String root) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT o.sequence,o.evidence_id,o.recorded_at,o.payload FROM"
                            + " evolution_evidence_sources x JOIN evolution_evidence_observations o"
                            + " ON o.evidence_id=x.head_id WHERE x.root_id=?")) {
            s.setString(1, root);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    /**
     * Exact mapping+skill; sequence keyset cursor; includes excluded history so consumers can
     * explain it.
     */
    public List<EvidenceView> forSkill(
            SkillRef skill, EvidenceMappingRef mapping, long afterSequence, int limit) {
        try (var c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            var views = forSkill(c, skill, mapping, afterSequence, limit);
            c.commit();
            return views;
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read local evidence", failure);
        }
    }

    /**
     * Caller-owned read snapshot for coherent multi-page collection. Never commits or closes it.
     */
    public List<EvidenceView> forSkill(
            Connection c, SkillRef skill, EvidenceMappingRef mapping, long afterSequence, int limit)
            throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Read snapshot transaction required");
        if (afterSequence < 0 || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Bounded page required");
        if (new EvidenceMappingRepository().find(c, mapping).isEmpty())
            throw new IllegalArgumentException("Unknown exact mapping");
        var views = new ArrayList<EvidenceView>();
        try (var s =
                c.prepareStatement(
                        """
                            SELECT o.sequence,o.evidence_id,o.recorded_at,o.payload,x.head_id,i.selector,
                              EXISTS(SELECT 1 FROM evolution_evidence_mappings n WHERE n.mapping_id=i.mapping_id AND n.mapping_version>i.mapping_version AND n.published=1) AS mapping_old
                            FROM evolution_evidence_observations o JOIN evolution_evidence_sources x USING(root_id)
                            JOIN evolution_evidence_interpretations i USING(evidence_id)
                            JOIN evolution_evidence_mapping_rules r ON r.mapping_id=i.mapping_id AND r.mapping_version=i.mapping_version AND r.source_type=x.source_type AND r.selector=i.selector
                            WHERE i.mapping_id=? AND i.mapping_version=? AND r.skill_id=? AND r.definition_version=? AND o.sequence>?
                            ORDER BY o.sequence LIMIT ?
                        """)) {
            s.setString(1, mapping.mappingId());
            s.setInt(2, mapping.version());
            s.setString(3, skill.skillId());
            s.setInt(4, skill.definitionVersion());
            s.setLong(5, afterSequence);
            s.setInt(6, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var stored = read(r);
                    var o = stored.observation();
                    var excluded = EnumSet.noneOf(Exclusion.class);
                    var availability = o.availability();
                    if (!stored.id().equals(r.getString("head_id")))
                        excluded.add(Exclusion.SUPERSEDED);
                    if (r.getInt("mapping_old") == 1) excluded.add(Exclusion.MAPPING_SUPERSEDED);
                    if (o.availability() == Availability.RETRACTED)
                        excluded.add(Exclusion.RETRACTED);
                    else if (o.source().type() == SourceType.CHALLENGE_EVALUATION) {
                        if (!challenges.matches(c, o)) {
                            availability = Availability.SOURCE_UNAVAILABLE;
                            excluded.add(Exclusion.SOURCE_UNAVAILABLE);
                        }
                    } else {
                        var raw = sources.find(c, o.source());
                        if (raw.isEmpty()) {
                            availability = Availability.SOURCE_UNAVAILABLE;
                            excluded.add(Exclusion.SOURCE_UNAVAILABLE);
                        } else if (!raw.get().observation().sameFacts(o)
                                || !raw.get().selector().equals(r.getString("selector")))
                            excluded.add(Exclusion.SOURCE_CHANGED);
                    }
                    if (o.source().type() == SourceType.PROBLEM_ATTEMPT
                            && hasEvaluationAlias(c, o.source().recordId()))
                        excluded.add(Exclusion.EVALUATION_AUTHORITATIVE);
                    views.add(new EvidenceView(stored, mapping, skill, availability, excluded));
                }
            }
        }
        return List.copyOf(views);
    }

    private static boolean hasEvaluationAlias(Connection c, long attempt) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT 1 FROM evolution_evidence_evaluation_links WHERE"
                                + " problem_attempt_id=?")) {
            s.setLong(1, attempt);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private static StoredEvidence read(ResultSet r) throws SQLException {
        var o = EvidenceCodec.decode(r.getBytes("payload"));
        String id = r.getString("evidence_id");
        if (!id.equals(EvidenceIds.id(o.source())))
            throw new IllegalStateException("Corrupt evidence identity");
        return new StoredEvidence(
                r.getLong("sequence"), id, Instant.parse(r.getString("recorded_at")), o);
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Evidence append requires caller transaction");
        try (var s = c.createStatement();
                var r = s.executeQuery("PRAGMA foreign_keys")) {
            if (!r.next() || r.getInt(1) != 1)
                throw new IllegalArgumentException("Enable foreign keys before transaction");
        }
    }
}
