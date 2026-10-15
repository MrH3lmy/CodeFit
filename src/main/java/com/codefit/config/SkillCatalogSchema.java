package com.codefit.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Additive EV-02 schema. Draft rows exist only inside a publication transaction. */
final class SkillCatalogSchema {
    private SkillCatalogSchema() { }

    static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE evolution_skill_definitions (
                        skill_id TEXT NOT NULL CHECK(length(trim(skill_id)) > 0),
                        definition_version INTEGER NOT NULL CHECK(definition_version > 0 AND typeof(definition_version) = 'integer'),
                        domain_id TEXT NOT NULL CHECK(length(trim(domain_id)) > 0),
                        name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                        description TEXT NOT NULL CHECK(length(trim(description)) > 0),
                        lifecycle TEXT NOT NULL CHECK(lifecycle IN ('ACTIVE', 'RETIRED')),
                        published INTEGER NOT NULL DEFAULT 0 CHECK(published IN (0, 1)),
                        PRIMARY KEY(skill_id, definition_version)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE evolution_skill_behaviors (
                        skill_id TEXT NOT NULL,
                        definition_version INTEGER NOT NULL,
                        position INTEGER NOT NULL CHECK(position >= 0 AND typeof(position) = 'integer'),
                        behavior TEXT NOT NULL CHECK(length(trim(behavior)) > 0),
                        PRIMARY KEY(skill_id, definition_version, position),
                        UNIQUE(skill_id, definition_version, behavior),
                        FOREIGN KEY(skill_id, definition_version)
                            REFERENCES evolution_skill_definitions(skill_id, definition_version) ON DELETE RESTRICT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE evolution_skill_graphs (
                        graph_id TEXT NOT NULL CHECK(length(trim(graph_id)) > 0),
                        graph_version INTEGER NOT NULL CHECK(graph_version > 0 AND typeof(graph_version) = 'integer'),
                        published INTEGER NOT NULL DEFAULT 0 CHECK(published IN (0, 1)),
                        PRIMARY KEY(graph_id, graph_version)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE evolution_graph_skills (
                        graph_id TEXT NOT NULL,
                        graph_version INTEGER NOT NULL,
                        skill_id TEXT NOT NULL,
                        definition_version INTEGER NOT NULL,
                        PRIMARY KEY(graph_id, graph_version, skill_id),
                        UNIQUE(graph_id, graph_version, skill_id, definition_version),
                        FOREIGN KEY(graph_id, graph_version)
                            REFERENCES evolution_skill_graphs(graph_id, graph_version) ON DELETE RESTRICT,
                        FOREIGN KEY(skill_id, definition_version)
                            REFERENCES evolution_skill_definitions(skill_id, definition_version) ON DELETE RESTRICT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE evolution_skill_prerequisites (
                        graph_id TEXT NOT NULL,
                        graph_version INTEGER NOT NULL,
                        prerequisite_skill_id TEXT NOT NULL,
                        prerequisite_version INTEGER NOT NULL,
                        dependent_skill_id TEXT NOT NULL,
                        dependent_version INTEGER NOT NULL,
                        CHECK(prerequisite_skill_id <> dependent_skill_id),
                        PRIMARY KEY(graph_id, graph_version, prerequisite_skill_id, dependent_skill_id),
                        FOREIGN KEY(graph_id, graph_version, prerequisite_skill_id, prerequisite_version)
                            REFERENCES evolution_graph_skills(graph_id, graph_version, skill_id, definition_version) ON DELETE RESTRICT,
                        FOREIGN KEY(graph_id, graph_version, dependent_skill_id, dependent_version)
                            REFERENCES evolution_graph_skills(graph_id, graph_version, skill_id, definition_version) ON DELETE RESTRICT
                    )
                    """);
            statement.execute("CREATE INDEX evolution_skill_domain ON evolution_skill_definitions(domain_id, skill_id, definition_version)");
            statement.execute("""
                    CREATE TRIGGER evolution_skill_publish BEFORE UPDATE OF published ON evolution_skill_definitions
                    WHEN OLD.published = 0 AND NEW.published = 1
                    BEGIN
                        SELECT RAISE(ABORT, 'A skill requires ordered observable behaviors')
                        WHERE NOT EXISTS (SELECT 1 FROM evolution_skill_behaviors
                            WHERE skill_id = NEW.skill_id AND definition_version = NEW.definition_version
                            GROUP BY skill_id, definition_version
                            HAVING MIN(position) = 0 AND MAX(position) + 1 = COUNT(*));
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER evolution_graph_member_definition BEFORE INSERT ON evolution_graph_skills
                    WHEN NOT EXISTS (SELECT 1 FROM evolution_skill_definitions
                        WHERE skill_id = NEW.skill_id AND definition_version = NEW.definition_version AND published = 1)
                    BEGIN SELECT RAISE(ABORT, 'Graph member must reference a published skill definition'); END
                    """);
            statement.execute("""
                    CREATE TRIGGER evolution_graph_publish BEFORE UPDATE OF published ON evolution_skill_graphs
                    WHEN OLD.published = 0 AND NEW.published = 1
                    BEGIN
                        SELECT RAISE(ABORT, 'A graph requires skill members')
                        WHERE NOT EXISTS (SELECT 1 FROM evolution_graph_skills
                            WHERE graph_id = NEW.graph_id AND graph_version = NEW.graph_version);
                        SELECT RAISE(ABORT, 'Graph members must reference published skill definitions')
                        WHERE EXISTS (SELECT 1 FROM evolution_graph_skills m
                            LEFT JOIN evolution_skill_definitions d USING(skill_id, definition_version)
                            WHERE m.graph_id = NEW.graph_id AND m.graph_version = NEW.graph_version
                              AND (d.published IS NULL OR d.published <> 1));
                        WITH RECURSIVE reachable(start_id, node_id) AS (
                            SELECT prerequisite_skill_id, dependent_skill_id FROM evolution_skill_prerequisites
                            WHERE graph_id = NEW.graph_id AND graph_version = NEW.graph_version
                            UNION
                            SELECT r.start_id, e.dependent_skill_id FROM reachable r
                            JOIN evolution_skill_prerequisites e ON e.prerequisite_skill_id = r.node_id
                            WHERE e.graph_id = NEW.graph_id AND e.graph_version = NEW.graph_version
                        )
                        SELECT RAISE(ABORT, 'Prerequisite cycle in graph') FROM reachable WHERE start_id = node_id LIMIT 1;
                    END
                    """);
        }
        immutableHeader(connection, "evolution_skill_definitions");
        immutableHeader(connection, "evolution_skill_graphs");
        immutableChildren(connection, "evolution_skill_behaviors", "evolution_skill_definitions",
                "skill_id", "definition_version");
        immutableChildren(connection, "evolution_graph_skills", "evolution_skill_graphs", "graph_id", "graph_version");
        immutableChildren(connection, "evolution_skill_prerequisites", "evolution_skill_graphs", "graph_id", "graph_version");
    }

    private static void immutableHeader(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + table + "_starts_unpublished BEFORE INSERT ON " + table
                    + " WHEN NEW.published <> 0 BEGIN SELECT RAISE(ABORT, 'Catalog versions must be assembled before publication'); END");
            for (String operation : new String[]{"UPDATE", "DELETE"}) {
                statement.execute("CREATE TRIGGER " + table + "_immutable_" + operation + " BEFORE " + operation
                        + " ON " + table + " WHEN OLD.published = 1 BEGIN SELECT RAISE(ABORT, 'Published catalog versions are immutable'); END");
            }
        }
    }

    private static void immutableChildren(Connection connection, String table, String parent, String id, String version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String operation : new String[]{"INSERT", "UPDATE", "DELETE"}) {
                String oldGuard = "EXISTS (SELECT 1 FROM " + parent + " WHERE " + id + " = OLD." + id
                        + " AND " + version + " = OLD." + version + " AND published = 1)";
                String newGuard = oldGuard.replace("OLD.", "NEW.");
                String guard = operation.equals("INSERT") ? newGuard : operation.equals("DELETE") ? oldGuard : oldGuard + " OR " + newGuard;
                statement.execute("CREATE TRIGGER " + table + "_immutable_" + operation + " BEFORE " + operation
                        + " ON " + table + " WHEN " + guard + " BEGIN SELECT RAISE(ABORT, 'Published catalog members are immutable'); END");
            }
        }
    }
}
