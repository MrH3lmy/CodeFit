package com.codefit.repository;

import com.codefit.evolution.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Explicit per-track planning selection; never publishes taxonomy, mappings or learner estimates.
 */
public final class GraphSelectionRepository {
    public record Selection(String track, String domain, GraphRef graph, long revision) {}

    public record Command(
            String id,
            String profile,
            String origin,
            String track,
            GraphRef target,
            long expected) {
        public Command {
            for (String idValue : List.of(id, profile, origin))
                if (!UUID.fromString(idValue).toString().equals(idValue))
                    throw new IllegalArgumentException("INVALID_INPUT");
            identifier(track);
            if (target.graphId().length() > 128 || expected < 0 || expected > 9007199254740990L)
                throw new IllegalArgumentException("INVALID_INPUT");
        }
    }

    public record Receipt(
            Command command,
            String state,
            String reason,
            GraphRef selected,
            long revision,
            Instant at) {}

    public record Lifecycle(long revision, String state, GraphRef replacement) {}

    public Selection selected(Connection c, String track) throws SQLException {
        snapshot(c);
        identifier(track);
        try (var q =
                c.prepareStatement(
                        "SELECT domain_id,graph_id,graph_version,revision FROM"
                                + " evolution_graph_selection_tracks WHERE track_id=?")) {
            q.setString(1, track);
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("TRACK_UNAVAILABLE");
                String domain = r.getString(1);
                identifier(domain);
                long revision = r.getLong(4);
                if (revision < 0 || revision > 9007199254740990L)
                    throw new SQLException("PROFILE_INVALID");
                return new Selection(track, domain, ref(r, 2), revision);
            }
        }
    }

    public Lifecycle lifecycle(Connection c, GraphRef graph) throws SQLException {
        snapshot(c);
        try (var q =
                c.prepareStatement(
                        "SELECT revision,state,replacement_id,replacement_version FROM"
                                + " evolution_graph_selection_lifecycle WHERE graph_id=? AND"
                                + " graph_version=? ORDER BY revision DESC LIMIT 1")) {
            bind(q, 1, graph);
            try (var r = q.executeQuery()) {
                if (!r.next()) return new Lifecycle(0, "ACTIVE", null);
                String state = r.getString(2);
                if (!Set.of("ACTIVE", "RETIRED").contains(state) || r.getLong(1) < 1)
                    throw new SQLException("PROFILE_INVALID");
                return new Lifecycle(
                        r.getLong(1), state, r.getString(3) == null ? null : ref(r, 3));
            }
        }
    }

    /**
     * Curator-only append. Retirement never edits the immutable graph or switches its consumers.
     */
    public void lifecycle(
            Connection c, GraphRef graph, long expected, String state, GraphRef replacement)
            throws SQLException {
        lock(c);
        if (expected < 0 || expected >= 9007199254740990L)
            throw new IllegalArgumentException("INVALID_INPUT");
        if (!Set.of("ACTIVE", "RETIRED").contains(state)
                || (state.equals("ACTIVE") && replacement != null)
                || graph.equals(replacement)) throw new IllegalArgumentException("INVALID_INPUT");
        var catalog = new SkillCatalogRepository();
        catalog.findGraph(c, graph)
                .orElseThrow(() -> new IllegalArgumentException("GRAPH_UNAVAILABLE"));
        if (replacement != null)
            catalog.findGraph(c, replacement)
                    .orElseThrow(() -> new IllegalArgumentException("GRAPH_UNAVAILABLE"));
        var old = lifecycle(c, graph);
        if (old.revision() != expected) throw new IllegalArgumentException("SELECTION_CONFLICT");
        try (var q =
                c.prepareStatement(
                        "INSERT INTO evolution_graph_selection_lifecycle VALUES(?,?,?,?,?,?)")) {
            bind(q, 1, graph);
            q.setLong(3, expected + 1);
            q.setString(4, state);
            q.setString(5, replacement == null ? null : replacement.graphId());
            if (replacement == null) q.setNull(6, Types.INTEGER);
            else q.setInt(6, replacement.graphVersion());
            q.executeUpdate();
        }
    }

    public Receipt receipt(Connection c, Command command) throws SQLException {
        snapshot(c);
        try (var q =
                c.prepareStatement(
                        "SELECT"
                            + " profile_id,origin_session,track_id,target_id,target_version,expected_revision,state,reason,selected_id,selected_version,revision,recorded_at"
                            + " FROM evolution_graph_selection_receipts WHERE command_id=?")) {
            q.setString(1, command.id());
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                var stored =
                        new Command(
                                command.id(),
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                ref(r, 4),
                                r.getLong(6));
                if (!stored.equals(command))
                    throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                return new Receipt(
                        stored,
                        r.getString(7),
                        r.getString(8),
                        ref(r, 9),
                        r.getLong(11),
                        Instant.parse(r.getString(12)));
            }
        }
    }

    /** Validate the exact catalog on the same transaction as pointer CAS and immutable receipt. */
    public Receipt choose(Connection c, Command command, Instant at) throws SQLException {
        lock(c);
        var replay = receipt(c, command);
        if (replay != null) return replay;
        var old = selected(c, command.track());
        String reason =
                old.revision() != command.expected()
                        ? "SELECTION_CONFLICT"
                        : eligibility(c, command.target(), old.domain());
        String state =
                reason.equals("ELIGIBLE")
                        ? (old.graph().equals(command.target()) ? "UNCHANGED" : "SELECTED")
                        : "REJECTED";
        var selected = state.equals("SELECTED") ? command.target() : old.graph();
        if (state.equals("SELECTED") && old.revision() >= 9007199254740990L)
            throw new IllegalArgumentException("INVALID_INPUT");
        long revision = old.revision() + (state.equals("SELECTED") ? 1 : 0);
        if (!state.equals("REJECTED"))
            reason = state.equals("SELECTED") ? "SAVED" : "ALREADY_SELECTED";
        if (state.equals("SELECTED")) {
            try (var q =
                    c.prepareStatement(
                            "UPDATE evolution_graph_selection_tracks SET"
                                    + " graph_id=?,graph_version=?,revision=? WHERE track_id=? AND"
                                    + " revision=?")) {
                bind(q, 1, selected);
                q.setLong(3, revision);
                q.setString(4, command.track());
                q.setLong(5, command.expected());
                if (q.executeUpdate() != 1) throw new SQLException("SELECTION_CONFLICT");
            }
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO evolution_graph_selection_receipts"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            q.setString(1, command.id());
            q.setString(2, command.profile());
            q.setString(3, command.origin());
            q.setString(4, command.track());
            bind(q, 5, command.target());
            q.setLong(7, command.expected());
            q.setString(8, state);
            q.setString(9, reason);
            bind(q, 10, selected);
            q.setLong(12, revision);
            q.setString(13, at.toString());
            q.executeUpdate();
        }
        return new Receipt(command, state, reason, selected, revision, at);
    }

    public String eligibility(Connection c, GraphRef target, String domain) throws SQLException {
        var graph = new SkillCatalogRepository().findBoundedGraph(c, target);
        if (graph.isEmpty()) return "GRAPH_MISSING";
        if (lifecycle(c, target).state().equals("RETIRED")) return "GRAPH_RETIRED";
        if (graph.get().definitions().stream()
                .anyMatch(d -> d.lifecycle() != SkillLifecycle.ACTIVE)) return "RETIRED_SKILL";
        if (graph.get().definitions().stream().anyMatch(d -> !d.domainId().equals(domain)))
            return "DOMAIN_UNSUPPORTED";
        return "ELIGIBLE";
    }

    private static GraphRef ref(ResultSet r, int i) throws SQLException {
        long v = r.getLong(i + 1);
        String id = r.getString(i);
        identifier(id);
        if (v < 1 || v > Integer.MAX_VALUE) throw new SQLException("PROFILE_INVALID");
        return new GraphRef(id, (int) v);
    }

    private static void bind(PreparedStatement q, int i, GraphRef g) throws SQLException {
        q.setString(i, g.graphId());
        q.setInt(i + 1, g.graphVersion());
    }

    private static void identifier(String s) {
        if (s == null || s.length() > 128 || !s.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"))
            throw new IllegalArgumentException("INVALID_INPUT");
    }

    private static void snapshot(Connection c) throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Caller-owned transaction required");
    }

    private static void lock(Connection c) throws SQLException {
        snapshot(c);
        try (var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE evolution_graph_selection_tracks SET revision=revision WHERE 0");
        }
    }
}
