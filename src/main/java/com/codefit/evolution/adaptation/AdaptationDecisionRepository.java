package com.codefit.evolution.adaptation;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationInput.*;
import com.codefit.evolution.quest.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Immutable owner of decision inputs and learner responses. No attempt/evidence writes. */
public final class AdaptationDecisionRepository {
    public record Decision(
            long sequence,
            String id,
            String inputSha,
            AdaptationInput input,
            NextQuestDecision result) {}

    public record Choice(
            long sequence,
            LearnerCommand command,
            GraphRef graph,
            Instant chosenAt,
            Preferences preferences) {}

    Decision save(
            Connection c,
            AdaptationInput in,
            NextQuestDecision result,
            byte[] bytes,
            String sha,
            byte[] outputBytes,
            String outputSha)
            throws SQLException {
        String id = "decision-" + sha;
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_adaptation_decisions(id,input_sha,policy,configuration,graph_id,graph_version,preferences_version,as_of,inputs,output_sha,outputs)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON"
                            + " CONFLICT(policy,configuration,input_sha) DO NOTHING")) {
            s.setString(1, id);
            s.setString(2, sha);
            s.setString(3, in.policy());
            s.setString(4, in.configuration());
            s.setString(5, in.graph().graphId());
            s.setInt(6, in.graph().graphVersion());
            s.setLong(7, in.preferences().version());
            s.setString(8, in.asOf().toString());
            s.setBytes(9, bytes);
            s.setString(10, outputSha);
            s.setBytes(11, outputBytes);
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement("SELECT * FROM evolution_adaptation_decisions WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || !Arrays.equals(bytes, r.getBytes("inputs"))
                        || !Arrays.equals(outputBytes, r.getBytes("outputs"))
                        || !outputSha.equals(r.getString("output_sha"))
                        || !in.policy().equals(r.getString("policy"))
                        || !in.configuration().equals(r.getString("configuration"))
                        || !in.graph().graphId().equals(r.getString("graph_id"))
                        || in.graph().graphVersion() != r.getInt("graph_version")
                        || in.preferences().version() != r.getLong("preferences_version")
                        || !in.asOf().toString().equals(r.getString("as_of")))
                    throw new IllegalStateException("Conflicting adaptation decision");
                return new Decision(r.getLong(1), id, sha, in, result);
            }
        }
    }

    Optional<Decision> find(Connection c, String id) throws SQLException {
        try (var s =
                c.prepareStatement("SELECT * FROM evolution_adaptation_decisions WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public Optional<Decision> find(String id) {
        try (var c = DatabaseConfig.getConnection()) {
            return find(c, id);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect decision", e);
        }
    }

    public List<Decision> history(long after, int limit) {
        bounds(after, limit);
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT * FROM evolution_adaptation_decisions WHERE sequence>?"
                                        + " ORDER BY sequence LIMIT ?")) {
            s.setLong(1, after);
            s.setInt(2, limit);
            var result = new ArrayList<Decision>();
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(read(r));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect decision history", e);
        }
    }

    private Decision read(ResultSet r) throws SQLException {
        byte[] bytes = r.getBytes("inputs");
        String sha = CatalogCodec.digest(bytes);
        if (!sha.equals(r.getString("input_sha")) || !("decision-" + sha).equals(r.getString("id")))
            throw new IllegalStateException("Corrupt adaptation input digest");
        var in = AdaptationCodec.decodeAdaptationInput(bytes);
        if (!in.policy().equals(r.getString("policy"))
                || !in.configuration().equals(r.getString("configuration"))
                || !in.graph().graphId().equals(r.getString("graph_id"))
                || in.graph().graphVersion() != r.getInt("graph_version")
                || in.preferences().version() != r.getLong("preferences_version")
                || !in.asOf().toString().equals(r.getString("as_of")))
            throw new IllegalStateException("Conflicting adaptation input identity");
        var expected = new AdaptiveQuestPolicy().decide(in);
        byte[] outputBytes = r.getBytes("outputs");
        if (!CatalogCodec.digest(outputBytes).equals(r.getString("output_sha")))
            throw new IllegalStateException("Corrupt adaptation result digest");
        var saved = AdaptationCodec.decodeNextQuestDecision(outputBytes);
        if (!saved.equals(expected))
            throw new IllegalStateException("Pinned decision policy/result mismatch");
        return new Decision(r.getLong("sequence"), r.getString("id"), sha, in, saved);
    }

    Preferences preferences(Connection c, Instant asOf) throws SQLException {
        Preferences prefs = Preferences.initial();
        try (var s =
                        c.prepareStatement(
                                "SELECT * FROM evolution_adaptation_choices ORDER BY sequence");
                var r = s.executeQuery()) {
            int count = 0;
            while (r.next()) {
                if (++count > 10000)
                    throw new IllegalStateException("Preference history exceeds bounded V1 input");
                var choice = readChoice(r);
                if (!choice.chosenAt().isAfter(asOf)) prefs = choice.preferences();
            }
        }
        return prefs;
    }

    Optional<Choice> choice(Connection c, String command) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT * FROM evolution_adaptation_choices WHERE command_id=?")) {
            s.setString(1, command);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(readChoice(r)) : Optional.empty();
            }
        }
    }

    public Optional<Choice> choice(String command) {
        try (var c = DatabaseConfig.getConnection()) {
            return choice(c, command);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect learner response", e);
        }
    }

    public List<Choice> choices(long after, int limit) {
        bounds(after, limit);
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT * FROM evolution_adaptation_choices WHERE sequence>? ORDER"
                                        + " BY sequence LIMIT ?")) {
            s.setLong(1, after);
            s.setInt(2, limit);
            var result = new ArrayList<Choice>();
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(readChoice(r));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect learner responses", e);
        }
    }

    Choice append(
            Connection c, LearnerCommand command, GraphRef graph, Instant at, Preferences current)
            throws SQLException {
        var existing = choice(c, command.id());
        if (existing.isPresent()) {
            if (!existing.get().command().equals(command))
                throw new IllegalArgumentException("Learner command identity conflict");
            return existing.get();
        }
        long next;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT COALESCE(MAX(sequence),0)+1 FROM"
                                        + " evolution_adaptation_choices")) {
            r.next();
            next = r.getLong(1);
        }
        // Clock reversal must not make an older preference revision active again.
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT chosen_at FROM evolution_adaptation_choices ORDER BY"
                                        + " sequence DESC LIMIT 1")) {
            if (r.next() && at.isBefore(Instant.parse(r.getString(1))))
                throw new IllegalStateException("Clock predates latest learner choice");
        }
        var declined = new TreeSet<>(current.declined());
        var deferred = new TreeMap<>(current.deferred());
        var track = current.track();
        var goal = current.goal();
        int budget = current.budgetMinutes();
        boolean paused = current.paused();
        switch (command.kind()) {
            case PAUSE -> paused = true;
            case RESUME -> paused = false;
            case SWITCH_TRACK -> track = command.track();
            case CHANGE_GOAL -> goal = command.goal();
            case CHANGE_BUDGET -> budget = command.budgetMinutes();
            case DECLINE -> {
                declined.add(command.challenge().id());
                deferred.remove(command.challenge().id());
            }
            case DEFER -> {
                if (!command.deferUntil().isAfter(at))
                    throw new IllegalArgumentException("Defer must be in the UTC future");
                deferred.put(command.challenge().id(), command.deferUntil());
            }
            case RESUME_CANDIDATE -> {
                declined.remove(command.challenge().id());
                deferred.remove(command.challenge().id());
            }
            default -> {}
        }
        var updated = new Preferences(next, track, goal, budget, paused, declined, deferred);
        var bytes = AdaptationCodec.encode(command);
        var prefBytes = AdaptationCodec.encode(updated);
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_adaptation_choices(sequence,command_id,payload_sha,payload,decision_id,chosen_at,graph_id,graph_version,preferences_sha,preferences)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            s.setLong(1, next);
            s.setString(2, command.id());
            s.setString(3, CatalogCodec.digest(bytes));
            s.setBytes(4, bytes);
            s.setString(5, command.decisionId());
            s.setString(6, at.toString());
            s.setString(7, graph.graphId());
            s.setInt(8, graph.graphVersion());
            s.setString(9, CatalogCodec.digest(prefBytes));
            s.setBytes(10, prefBytes);
            s.executeUpdate();
        }
        return new Choice(next, command, graph, at, updated);
    }

    private Choice readChoice(ResultSet r) throws SQLException {
        byte[] payload = r.getBytes("payload"), prefs = r.getBytes("preferences");
        if (!CatalogCodec.digest(payload).equals(r.getString("payload_sha"))
                || !CatalogCodec.digest(prefs).equals(r.getString("preferences_sha")))
            throw new IllegalStateException("Corrupt learner choice digest");
        var command = AdaptationCodec.decodeLearnerCommand(payload);
        var preferences = AdaptationCodec.decodePreferences(prefs);
        if (!command.id().equals(r.getString("command_id"))
                || !Objects.equals(command.decisionId(), r.getString("decision_id"))
                || preferences.version() != r.getLong("sequence"))
            throw new IllegalStateException("Conflicting learner choice identity");
        return new Choice(
                r.getLong("sequence"),
                command,
                new GraphRef(r.getString("graph_id"), r.getInt("graph_version")),
                Instant.parse(r.getString("chosen_at")),
                preferences);
    }

    private static void bounds(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 128)
            throw new IllegalArgumentException("Bounded journal page required");
    }
}
