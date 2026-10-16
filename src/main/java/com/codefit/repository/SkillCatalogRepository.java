package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.GraphRef;
import com.codefit.evolution.SkillDefinition;
import com.codefit.evolution.SkillGraph;
import com.codefit.evolution.SkillLifecycle;
import com.codefit.evolution.SkillPrerequisite;
import com.codefit.evolution.SkillRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One local catalog repository. Publication is atomic and insert-only: identical replay is harmless,
 * conflicting reuse of a version is rejected. Caller-owned transaction overloads never commit.
 */
public class SkillCatalogRepository {
    public void publishDefinition(SkillDefinition definition) {
        write(connection -> publishDefinition(connection, definition));
    }

    public void publishGraph(SkillGraph graph) {
        write(connection -> publishGraph(connection, graph));
    }

    public Optional<SkillDefinition> findSkill(SkillRef ref) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findSkill(connection, ref);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load skill " + ref, exception);
        }
    }

    /** All historical versions, including RETIRED, sorted by stable ID then definition version. */
    public List<SkillDefinition> listSkillsInDomain(String domainId) {
        if (domainId == null || domainId.isBlank()) throw new IllegalArgumentException("Domain ID is required");
        try (Connection connection = DatabaseConfig.getConnection()) {
            return listSkillsInDomain(connection, domainId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to list skills in domain " + domainId, exception);
        }
    }

    /** Reads all published versions and ordered behaviors in one query on the caller's snapshot. */
    public List<SkillDefinition> listSkillsInDomain(Connection connection, String domainId) throws SQLException {
        if (domainId == null || domainId.isBlank()) throw new IllegalArgumentException("Domain ID is required");
        Map<SkillRef, DefinitionRows> definitions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.skill_id, d.definition_version, d.domain_id, d.name, d.description, d.lifecycle,
                       b.position, b.behavior
                FROM evolution_skill_definitions d
                LEFT JOIN evolution_skill_behaviors b USING(skill_id, definition_version)
                WHERE d.domain_id = ? AND d.published = 1
                ORDER BY d.skill_id, d.definition_version, b.position
                """)) {
            statement.setString(1, domainId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SkillRef ref = new SkillRef(rows.getString(1), rows.getInt(2));
                    DefinitionRows definition = definitions.get(ref);
                    if (definition == null) {
                        definition = new DefinitionRows(ref, rows.getString(3), rows.getString(4),
                                rows.getString(5), rows.getString(6));
                        definitions.put(ref, definition);
                    }
                    definition.addBehavior(rows.getInt(7), rows.getString(8), rows.wasNull());
                }
            }
        }
        return definitions.values().stream().map(DefinitionRows::build).toList();
    }

    public Optional<SkillGraph> findGraph(GraphRef ref) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<SkillGraph> graph = findGraph(connection, ref);
                connection.commit();
                return graph;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load skill graph " + ref, exception);
        }
    }

    /** Requires a transaction and foreign_keys enabled BEFORE starting it; rollback on any failure. */
    public void publishDefinition(Connection connection, SkillDefinition definition) throws SQLException {
        requireTransaction(connection);
        int inserted;
        // First operation acquires the write lock, before a read/compare: concurrent publishers
        // cannot both read an absent version then race to overwrite or fail with a snapshot upgrade.
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO evolution_skill_definitions
                    (skill_id, definition_version, domain_id, name, description, lifecycle)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(skill_id, definition_version) DO NOTHING
                """)) {
            bindRef(statement, 1, definition.ref());
            statement.setString(3, definition.domainId());
            statement.setString(4, definition.name());
            statement.setString(5, definition.description());
            statement.setString(6, definition.lifecycle().name());
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            if (!requireSkill(connection, definition.ref()).equals(definition)) {
                throw new IllegalArgumentException("Conflicting immutable skill definition " + definition.ref());
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO evolution_skill_behaviors (skill_id, definition_version, position, behavior) VALUES (?, ?, ?, ?)
                """)) {
            for (int index = 0; index < definition.observableBehaviors().size(); index++) {
                bindRef(statement, 1, definition.ref());
                statement.setInt(3, index);
                statement.setString(4, definition.observableBehaviors().get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE evolution_skill_definitions SET published = 1 WHERE skill_id = ? AND definition_version = ?
                """)) {
            bindRef(statement, 1, definition.ref());
            statement.executeUpdate();
        }
    }

    /** Graph members must already be published, with exactly matching immutable content. */
    public void publishGraph(Connection connection, SkillGraph graph) throws SQLException {
        requireTransaction(connection);
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO evolution_skill_graphs (graph_id, graph_version) VALUES (?, ?)
                ON CONFLICT(graph_id, graph_version) DO NOTHING
                """)) {
            bindGraph(statement, graph.ref());
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            SkillGraph existing = findGraph(connection, graph.ref()).orElseThrow(() ->
                    new IllegalStateException("Unpublished/incomplete graph " + graph.ref()));
            if (!existing.equals(graph)) throw new IllegalArgumentException("Conflicting immutable skill graph " + graph.ref());
            return;
        }
        for (SkillDefinition definition : graph.definitions()) {
            if (!requireSkill(connection, definition.ref()).equals(definition)) {
                throw new IllegalArgumentException("Graph " + graph.ref() + " conflicts with published definition " + definition.ref());
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO evolution_graph_skills (graph_id, graph_version, skill_id, definition_version) VALUES (?, ?, ?, ?)
                """)) {
            for (SkillDefinition definition : graph.definitions()) {
                bindGraph(statement, graph.ref());
                bindRef(statement, 3, definition.ref());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO evolution_skill_prerequisites
                    (graph_id, graph_version, prerequisite_skill_id, prerequisite_version, dependent_skill_id, dependent_version)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (SkillPrerequisite edge : graph.prerequisites()) {
                bindGraph(statement, graph.ref());
                bindRef(statement, 3, edge.prerequisite());
                bindRef(statement, 5, edge.dependent());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE evolution_skill_graphs SET published = 1 WHERE graph_id = ? AND graph_version = ?
                """)) {
            bindGraph(statement, graph.ref());
            statement.executeUpdate();
        }
    }

    private Optional<SkillDefinition> findSkill(Connection connection, SkillRef ref) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT domain_id, name, description, lifecycle FROM evolution_skill_definitions
                WHERE skill_id = ? AND definition_version = ? AND published = 1
                """)) {
            bindRef(statement, 1, ref);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new SkillDefinition(ref, row.getString(1), row.getString(2), row.getString(3),
                        behaviors(connection, ref), SkillLifecycle.valueOf(row.getString(4))));
            }
        }
    }

    private List<String> behaviors(Connection connection, SkillRef ref) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT position, behavior FROM evolution_skill_behaviors
                WHERE skill_id = ? AND definition_version = ? ORDER BY position
                """)) {
            bindRef(statement, 1, ref);
            List<String> behaviors = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt(1) != behaviors.size()) throw new IllegalStateException("Incomplete behaviors for " + ref);
                    behaviors.add(rows.getString(2));
                }
            }
            return List.copyOf(behaviors);
        }
    }

    private SkillDefinition requireSkill(Connection connection, SkillRef ref) throws SQLException {
        return findSkill(connection, ref).orElseThrow(() -> new IllegalArgumentException("Missing published skill definition " + ref));
    }

    /** Read the exact graph on a caller-owned catalog validation snapshot. */
    public Optional<SkillGraph> findGraph(Connection connection, GraphRef ref) throws SQLException {
        Map<SkillRef, DefinitionRows> definitions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.skill_id, m.definition_version, d.domain_id, d.name, d.description, d.lifecycle,
                       b.position, b.behavior
                FROM evolution_skill_graphs g
                LEFT JOIN evolution_graph_skills m USING(graph_id, graph_version)
                LEFT JOIN evolution_skill_definitions d
                    ON d.skill_id = m.skill_id AND d.definition_version = m.definition_version AND d.published = 1
                LEFT JOIN evolution_skill_behaviors b
                    ON b.skill_id = m.skill_id AND b.definition_version = m.definition_version
                WHERE g.graph_id = ? AND g.graph_version = ? AND g.published = 1
                ORDER BY m.skill_id, m.definition_version, b.position
                """)) {
            bindGraph(statement, ref);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                do {
                    String skillId = rows.getString(1);
                    if (skillId == null) continue;
                    SkillRef member = new SkillRef(skillId, rows.getInt(2));
                    if (rows.getString(3) == null) {
                        throw new IllegalArgumentException("Missing published skill definition " + member);
                    }
                    DefinitionRows definition = definitions.get(member);
                    if (definition == null) {
                        definition = new DefinitionRows(member, rows.getString(3), rows.getString(4),
                                rows.getString(5), rows.getString(6));
                        definitions.put(member, definition);
                    }
                    definition.addBehavior(rows.getInt(7), rows.getString(8), rows.wasNull());
                } while (rows.next());
            }
        }
        List<SkillPrerequisite> edges = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT prerequisite_skill_id, prerequisite_version, dependent_skill_id, dependent_version
                FROM evolution_skill_prerequisites WHERE graph_id = ? AND graph_version = ?
                """)) {
            bindGraph(statement, ref);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) edges.add(new SkillPrerequisite(new SkillRef(rows.getString(1), rows.getInt(2)),
                        new SkillRef(rows.getString(3), rows.getInt(4))));
            }
        }
        return Optional.of(new SkillGraph(ref, definitions.values().stream().map(DefinitionRows::build).toList(), edges));
    }

    private static final class DefinitionRows {
        private final SkillRef ref;
        private final String domainId;
        private final String name;
        private final String description;
        private final SkillLifecycle lifecycle;
        private final List<String> behaviors = new ArrayList<>();

        private DefinitionRows(SkillRef ref, String domainId, String name, String description, String lifecycle) {
            this.ref = ref;
            this.domainId = domainId;
            this.name = name;
            this.description = description;
            this.lifecycle = SkillLifecycle.valueOf(lifecycle);
        }

        private void addBehavior(int position, String behavior, boolean missing) {
            if (missing) return;
            if (position != behaviors.size()) throw new IllegalStateException("Incomplete behaviors for " + ref);
            behaviors.add(behavior);
        }

        private SkillDefinition build() {
            return new SkillDefinition(ref, domainId, name, description, behaviors, lifecycle);
        }
    }

    private static void bindRef(PreparedStatement statement, int offset, SkillRef ref) throws SQLException {
        statement.setString(offset, ref.skillId());
        statement.setInt(offset + 1, ref.definitionVersion());
    }

    private static void bindGraph(PreparedStatement statement, GraphRef ref) throws SQLException {
        statement.setString(1, ref.graphId());
        statement.setInt(2, ref.graphVersion());
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Catalog publication requires a caller-owned transaction");
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys"); ResultSet row = statement.executeQuery()) {
            if (!row.next() || row.getInt(1) != 1) throw new IllegalArgumentException("Enable foreign_keys before the catalog transaction");
        }
    }

    @FunctionalInterface
    private interface Publication { void run(Connection connection) throws SQLException; }

    private void write(Publication publication) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys = ON")) { statement.execute(); }
            connection.setAutoCommit(false);
            try {
                publication.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to publish skill catalog version: " + exception.getMessage(), exception);
        }
    }
}
