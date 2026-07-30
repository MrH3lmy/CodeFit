package com.codefit.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemGuidanceSchemaMigrationTest {

    @Test
    void upgradesLegacyGuidanceTableAndKeepsExistingContent(@TempDir Path tempDir) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("guidance.db"))) {
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
            DatabaseConfig.createProblemSolvingTables(connection);
            long problemId = insertProblem(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE problem_guidance (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            problem_id INTEGER NOT NULL UNIQUE,
                            source TEXT NOT NULL DEFAULT 'LEARNER',
                            clarify_text TEXT,
                            observation_text TEXT,
                            approach_text TEXT,
                            explanation_text TEXT,
                            prerequisites TEXT,
                            reference_links TEXT,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                        )
                        """);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO problem_guidance
                        (problem_id, source, clarify_text, explanation_text, prerequisites, reference_links)
                    VALUES (?, 'CODEFIT', 'legacy clarify', 'legacy explanation', 'Arrays', 'https://example.test/ref')
                    """)) {
                statement.setLong(1, problemId);
                statement.executeUpdate();
            }

            invokeGuidanceMigration(connection);

            Set<String> columns = columns(connection, "problem_guidance");
            assertTrue(columns.contains("pseudocode_text"));
            assertTrue(columns.contains("complexity_notes"));
            assertTrue(columns.contains("common_mistakes_text"));

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE problem_guidance
                    SET pseudocode_text = ?, complexity_notes = ?, common_mistakes_text = ?
                    WHERE problem_id = ?
                    """)) {
                statement.setString(1, "legacy pseudocode");
                statement.setString(2, "O(n)");
                statement.setString(3, "off-by-one");
                statement.setLong(4, problemId);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM problem_guidance WHERE problem_id = ?")) {
                statement.setLong(1, problemId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("CODEFIT", resultSet.getString("source"));
                    assertEquals("legacy clarify", resultSet.getString("clarify_text"));
                    assertEquals("legacy explanation", resultSet.getString("explanation_text"));
                    assertEquals("Arrays", resultSet.getString("prerequisites"));
                    assertEquals("https://example.test/ref", resultSet.getString("reference_links"));
                    assertEquals("legacy pseudocode", resultSet.getString("pseudocode_text"));
                    assertEquals("O(n)", resultSet.getString("complexity_notes"));
                    assertEquals("off-by-one", resultSet.getString("common_mistakes_text"));
                }
            }
        }
    }

    private void invokeGuidanceMigration(Connection connection) throws Exception {
        Method method = DatabaseConfig.class.getDeclaredMethod("ensureProblemGuidanceSchema", Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, connection);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private long insertProblem(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problems (external_code, platform, title) VALUES ('MIG-162', 'TEST', 'Migration')",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }
}
