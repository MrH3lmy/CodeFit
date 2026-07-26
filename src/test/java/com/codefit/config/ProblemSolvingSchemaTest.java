package com.codefit.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the problem-solving schema (#142) directly at the SQL level, against a throwaway sqlite
 * file rather than the shared local {@code codefit.db}, the same way {@link SchemaMigratorTest}
 * isolates schema-level behavior. Covers: additive/backward-compatible creation over an existing
 * legacy database, the uniqueness constraints that keep repeated imports from duplicating data, and
 * the cascade relationships between a problem and its dependent rows.
 */
class ProblemSolvingSchemaTest {

    private Connection connection;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("legacy.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            // A minimal pre-#142 database: only the original flashcard tables exist.
            statement.execute("""
                    CREATE TABLE decks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        description TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE flashcards (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        deck_id INTEGER NOT NULL,
                        front TEXT NOT NULL,
                        back TEXT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO decks (name, description) VALUES ('Legacy Deck', 'pre-existing data')");
            statement.execute("INSERT INTO flashcards (deck_id, front, back) VALUES (1, 'Q', 'A')");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void createsAllFiveProblemSolvingTablesOverAnExistingDatabaseWithoutTouchingLegacyData() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);

        List<String> tableNames = List.of("problems", "roadmap_entries", "problem_progress",
                "problem_attempts", "problem_solving_sessions");
        for (String tableName : tableNames) {
            assertTrue(tableExists(tableName), "expected table " + tableName + " to exist");
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT name, description FROM decks WHERE id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("Legacy Deck", resultSet.getString("name"));
            assertEquals("pre-existing data", resultSet.getString("description"));
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT front, back FROM flashcards WHERE id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("Q", resultSet.getString("front"));
            assertEquals("A", resultSet.getString("back"));
        }
    }

    @Test
    void schemaCreationIsIdempotent() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        DatabaseConfig.createProblemSolvingTables(connection);

        assertTrue(tableExists("problems"));
    }

    @Test
    void repeatedPlatformAndExternalCodeIsRejectedAsADuplicateProblem() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        insertProblem("LeetCode", "1", "Two Sum");

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problems (external_code, platform, title) VALUES (?, ?, ?)")) {
            statement.setString(1, "1");
            statement.setString(2, "LeetCode");
            statement.setString(3, "Two Sum (duplicate import)");
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void sameExternalCodeOnADifferentPlatformIsAllowed() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long first = insertProblem("LeetCode", "1", "Two Sum");
        long second = insertProblem("Codeforces", "1", "Some Codeforces Problem 1A");

        assertTrue(first > 0 && second > 0 && first != second);
    }

    @Test
    void aProblemCanOccupyMultipleDifferentRoadmapStagesButNotTheSameStageTwice() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");

        insertRoadmapEntry(problemId, "A", 1);
        insertRoadmapEntry(problemId, "B", 1);

        assertThrows(SQLException.class, () -> insertRoadmapEntry(problemId, "A", 2),
                "the same problem must not be registered twice within stage A");
    }

    @Test
    void twoDifferentProblemsCannotOccupyTheSameRoadmapSlot() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long first = insertProblem("LeetCode", "1", "Two Sum");
        long second = insertProblem("LeetCode", "2", "Add Two Numbers");

        insertRoadmapEntry(first, "A", 1);

        assertThrows(SQLException.class, () -> insertRoadmapEntry(second, "A", 1),
                "roadmap slot A#1 is already occupied");
    }

    @Test
    void aProblemHasAtMostOneProgressRow() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_progress (problem_id, state) VALUES (?, 'NOT_STARTED')")) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_progress (problem_id, state) VALUES (?, 'IN_PROGRESS')")) {
            statement.setLong(1, problemId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void aProblemCanHaveManyAttemptsButNotDuplicateAttemptNumbers() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");

        insertAttempt(problemId, 1, "WA");
        insertAttempt(problemId, 2, "AC");

        assertEquals(2, countRows("problem_attempts", "problem_id = " + problemId));
        assertThrows(SQLException.class, () -> insertAttempt(problemId, 2, "AC"),
                "attempt_number must be unique per problem");
    }

    @Test
    void aProblemHasAtMostOneSolvingSession() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_solving_sessions (problem_id) VALUES (?)")) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_solving_sessions (problem_id) VALUES (?)")) {
            statement.setLong(1, problemId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void deletingAProblemCascadesToItsRoadmapEntryProgressAttemptsAndSession() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");
        insertRoadmapEntry(problemId, "A", 1);
        insertAttempt(problemId, 1, "AC");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_progress (problem_id, state) VALUES (?, 'SOLVED')")) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_solving_sessions (problem_id) VALUES (?)")) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        }

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM problems WHERE id = ?")) {
            delete.setLong(1, problemId);
            delete.executeUpdate();
        }

        assertEquals(0, countRows("roadmap_entries", "problem_id = " + problemId));
        assertEquals(0, countRows("problem_progress", "problem_id = " + problemId));
        assertEquals(0, countRows("problem_attempts", "problem_id = " + problemId));
        assertEquals(0, countRows("problem_solving_sessions", "problem_id = " + problemId));
    }

    @Test
    void deletingAFlashcardDeckNeverTouchesProblemSolvingTables() throws SQLException {
        DatabaseConfig.createProblemSolvingTables(connection);
        long problemId = insertProblem("LeetCode", "1", "Two Sum");
        insertRoadmapEntry(problemId, "A", 1);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flashcards");
            statement.executeUpdate("DELETE FROM decks");
        }

        assertEquals(1, countRows("problems", "id = " + problemId));
        assertEquals(1, countRows("roadmap_entries", "problem_id = " + problemId));
    }

    private long insertProblem(String platform, String externalCode, String title) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problems (external_code, platform, title) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, externalCode);
            statement.setString(2, platform);
            statement.setString(3, title);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertRoadmapEntry(long problemId, String stage, int sequenceOrder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO roadmap_entries (problem_id, stage, sequence_order) VALUES (?, ?, ?)")) {
            statement.setLong(1, problemId);
            statement.setString(2, stage);
            statement.setInt(3, sequenceOrder);
            statement.executeUpdate();
        }
    }

    private void insertAttempt(long problemId, int attemptNumber, String submissionResult) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO problem_attempts (problem_id, attempt_number, submission_result) VALUES (?, ?, ?)")) {
            statement.setLong(1, problemId);
            statement.setInt(2, attemptNumber);
            statement.setString(3, submissionResult);
            statement.executeUpdate();
        }
    }

    private int countRows(String tableName, String whereClause) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
