package com.codefit.evolution.mastery;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Immutable, replayable policy inputs. Earlier revisions remain readable after source withdrawal.
 */
public final class EstimateRevisionRepository {
    public record Revision(long id, String inputSha, MasteryEstimate estimate) {}

    EstimateInput.Epoch epoch(Connection c, GraphRef graph, Instant asOf) throws SQLException {
        var epoch = new EstimateInput.Epoch("initial", Instant.MIN);
        try (var s =
                c.prepareStatement(
                        "SELECT id,started_at,evidence_through FROM evolution_estimate_epochs WHERE"
                                + " graph_id=? AND graph_version=? ORDER BY sequence")) {
            s.setString(1, graph.graphId());
            s.setInt(2, graph.graphVersion());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var at = Instant.parse(r.getString(2));
                    if (!at.isAfter(asOf))
                        epoch = new EstimateInput.Epoch(r.getString(1), at, r.getLong(3));
                }
            }
        }
        return epoch;
    }

    /** Learner-initiated graph-scoped reset. Replay uses the original clock instant. */
    public EstimateInput.Epoch reset(GraphRef graph, String commandId, Clock clock) {
        ChallengePresentation.identity(commandId);
        if (commandId.equals("initial"))
            throw new IllegalArgumentException("Reserved initial epoch identity");
        return new QuestCatalogRepository()
                .transaction(
                        c -> {
                            try (var s =
                                    c.prepareStatement(
                                            "SELECT"
                                                + " graph_id,graph_version,started_at,evidence_through"
                                                + " FROM evolution_estimate_epochs WHERE id=?")) {
                                s.setString(1, commandId);
                                try (var r = s.executeQuery()) {
                                    if (r.next()) {
                                        if (!graph.equals(
                                                new GraphRef(r.getString(1), r.getInt(2))))
                                            throw new IllegalArgumentException(
                                                    "Reset identity conflict");
                                        return new EstimateInput.Epoch(
                                                commandId,
                                                Instant.parse(r.getString(3)),
                                                r.getLong(4));
                                    }
                                }
                            }
                            new com.codefit.repository.SkillCatalogRepository()
                                    .findGraph(c, graph)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Unknown exact graph"));
                            Instant at = clock.instant();
                            long through;
                            try (var statement = c.createStatement();
                                    var rows =
                                            statement.executeQuery(
                                                    "SELECT COALESCE(MAX(sequence),0) FROM"
                                                            + " evolution_evidence_observations")) {
                                rows.next();
                                through = rows.getLong(1);
                            }
                            if (at.isBefore(epoch(c, graph, Instant.MAX).startedAt()))
                                throw new IllegalStateException(
                                        "Clock predates the latest estimate reset");
                            try (var s =
                                    c.prepareStatement(
                                            "INSERT INTO"
                                                + " evolution_estimate_epochs(id,graph_id,graph_version,started_at,evidence_through)"
                                                + " VALUES(?,?,?,?,?)")) {
                                s.setString(1, commandId);
                                s.setString(2, graph.graphId());
                                s.setInt(3, graph.graphVersion());
                                s.setString(4, at.toString());
                                s.setLong(5, through);
                                s.executeUpdate();
                            }
                            return new EstimateInput.Epoch(commandId, at, through);
                        });
    }

    Revision save(Connection c, EstimateInput in, byte[] bytes, MasteryEstimate estimate)
            throws SQLException {
        String digest = CatalogCodec.digest(bytes);
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_estimate_revisions(input_sha,policy,graph_id,graph_version,skill_id,skill_version,epoch_id,as_of,inputs)"
                            + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(policy,input_sha) DO"
                            + " NOTHING")) {
            s.setString(1, digest);
            s.setString(2, estimate.policyVersion());
            s.setString(3, in.graph().graphId());
            s.setInt(4, in.graph().graphVersion());
            s.setString(5, in.skill().ref().skillId());
            s.setInt(6, in.skill().ref().definitionVersion());
            s.setString(7, in.epoch().id());
            s.setString(8, in.asOf().toString());
            s.setBytes(9, bytes);
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement(
                        "SELECT revision,policy,inputs FROM evolution_estimate_revisions WHERE"
                                + " input_sha=? AND policy=?")) {
            s.setString(1, digest);
            s.setString(2, estimate.policyVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || !r.getString(2).equals(estimate.policyVersion())
                        || !Arrays.equals(bytes, r.getBytes(3)))
                    throw new IllegalStateException("Conflicting estimate revision");
                return new Revision(r.getLong(1), digest, estimate);
            }
        }
    }

    Optional<Revision> existing(Connection c, byte[] bytes, MasteryEstimate estimate)
            throws SQLException {
        String sha = CatalogCodec.digest(bytes);
        try (var s =
                c.prepareStatement(
                        "SELECT revision,policy,inputs FROM evolution_estimate_revisions WHERE"
                                + " input_sha=? AND policy=?")) {
            s.setString(1, sha);
            s.setString(2, estimate.policyVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                if (!estimate.policyVersion().equals(r.getString(2))
                        || !Arrays.equals(bytes, r.getBytes(3)))
                    throw new IllegalStateException("Conflicting estimate revision");
                return Optional.of(new Revision(r.getLong(1), sha, estimate));
            }
        }
    }

    /**
     * Immediate predecessor for the exact graph/skill; immutable history, no live re-estimation.
     */
    public Optional<Revision> previous(Revision current) {
        var e = current.estimate();
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT revision FROM evolution_estimate_revisions WHERE graph_id=?"
                                    + " AND graph_version=? AND skill_id=? AND skill_version=? AND"
                                    + " revision<? ORDER BY revision DESC LIMIT 1")) {
            s.setString(1, e.graph().graphId());
            s.setInt(2, e.graph().graphVersion());
            s.setString(3, e.skill().skillId());
            s.setInt(4, e.skill().definitionVersion());
            s.setLong(5, current.id());
            try (var r = s.executeQuery()) {
                return r.next() ? find(r.getLong(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Estimate comparison unavailable", ex);
        }
    }

    /** Exact historical revision on the caller's coherent snapshot; bounded before decoding. */
    public Optional<Revision> find(Connection c, long revision) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT length(inputs) FROM evolution_estimate_revisions WHERE"
                            + " revision=?")) {
            q.setLong(1, revision);
            try (var r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                if (r.getLong(1) > 1024 * 1024)
                    throw new SQLException("Estimate input exceeds read bound");
            }
        }
        try (var q =
                c.prepareStatement("SELECT * FROM evolution_estimate_revisions WHERE revision=?")) {
            q.setLong(1, revision);
            try (var r = q.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public Optional<Revision> find(long revision) {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT * FROM evolution_estimate_revisions WHERE revision=?")) {
            s.setLong(1, revision);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read estimate revision", e);
        }
    }

    public List<Revision> history(GraphRef graph, SkillRef skill, long afterRevision, int limit) {
        if (afterRevision < 0 || limit < 1 || limit > 128)
            throw new IllegalArgumentException("Bounded revision page required");
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT * FROM evolution_estimate_revisions WHERE graph_id=? AND"
                                        + " graph_version=? AND skill_id=? AND skill_version=? AND"
                                        + " revision>? ORDER BY revision LIMIT ?")) {
            s.setString(1, graph.graphId());
            s.setInt(2, graph.graphVersion());
            s.setString(3, skill.skillId());
            s.setInt(4, skill.definitionVersion());
            s.setLong(5, afterRevision);
            s.setInt(6, limit);
            var result = new ArrayList<Revision>();
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(read(r));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read estimate history", e);
        }
    }

    private Revision read(ResultSet r) throws SQLException {
        var bytes = r.getBytes("inputs");
        String sha = r.getString("input_sha");
        if (!sha.equals(CatalogCodec.digest(bytes))
                || !OrdinalMasteryPolicy.VERSION.equals(r.getString("policy")))
            throw new IllegalStateException("Unavailable estimate input/policy version");
        var in = EstimateCodec.decode(bytes);
        if (!r.getString("graph_id").equals(in.graph().graphId())
                || r.getInt("graph_version") != in.graph().graphVersion()
                || !r.getString("skill_id").equals(in.skill().ref().skillId())
                || r.getInt("skill_version") != in.skill().ref().definitionVersion()
                || !r.getString("epoch_id").equals(in.epoch().id())
                || !r.getString("as_of").equals(in.asOf().toString()))
            throw new IllegalStateException("Corrupt estimate identity");
        return new Revision(r.getLong("revision"), sha, new OrdinalMasteryPolicy().estimate(in));
    }
}
