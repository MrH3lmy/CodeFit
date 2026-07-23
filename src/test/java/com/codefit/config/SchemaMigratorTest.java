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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    private Connection connection;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("legacy.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE flashcards (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        card_type TEXT NOT NULL,
                        accepted_answers TEXT,
                        review_count INTEGER NOT NULL DEFAULT 0,
                        card_state TEXT NOT NULL DEFAULT 'NEW',
                        introduced_at TEXT,
                        created_at TEXT NOT NULL DEFAULT '2026-01-01 00:00:00'
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void migratesLegacyPipeDelimitedAnswersToJsonArray() throws SQLException {
        long id = insertCard("RECALL", "@ControllerAdvice|@RestControllerAdvice");

        SchemaMigrator.migrate(connection);

        assertEquals("[\"@ControllerAdvice\",\"@RestControllerAdvice\"]", acceptedAnswers(id));
    }

    @Test
    void leavesRegexPatternCardsUntouched() throws SQLException {
        long id = insertCard("REGEX_PATTERN", "\\d+|\\w+");

        SchemaMigrator.migrate(connection);

        assertEquals("\\d+|\\w+", acceptedAnswers(id));
    }

    @Test
    void leavesCardsWithoutPipeUntouched() throws SQLException {
        long id = insertCard("RECALL", "extends");

        SchemaMigrator.migrate(connection);

        assertEquals("extends", acceptedAnswers(id));
    }

    @Test
    void leavesLinuxPipelineCommandsUntouched() throws SQLException {
        long id = insertCard("LINUX_COMMAND", "ps aux | grep java");

        SchemaMigrator.migrate(connection);

        assertEquals("ps aux | grep java", acceptedAnswers(id));
    }

    @Test
    void leavesSqlConcatenationUntouched() throws SQLException {
        long id = insertCard("SQL_QUERY", "SELECT first_name || ' ' || last_name FROM users");

        SchemaMigrator.migrate(connection);

        assertEquals("SELECT first_name || ' ' || last_name FROM users", acceptedAnswers(id));
    }

    @Test
    void leavesUnknownPipeContainingValuesUntouchedEvenIfTheyLookLikeAlternatives() throws SQLException {
        // Only the fixed set of known pre-#90 seeded values may be migrated. A user-authored card
        // that happens to contain "|" but isn't one of those exact known values must never be
        // rewritten, even though it superficially looks like "alt1|alt2".
        long id = insertCard("RECALL", "foo|bar");

        SchemaMigrator.migrate(connection);

        assertEquals("foo|bar", acceptedAnswers(id));
    }

    @Test
    void migrationIsIdempotent() throws SQLException {
        long id = insertCard("RECALL", "mvn test|./mvnw test");

        SchemaMigrator.migrate(connection);
        String afterFirstRun = acceptedAnswers(id);
        SchemaMigrator.migrate(connection);
        String afterSecondRun = acceptedAnswers(id);

        assertEquals(afterFirstRun, afterSecondRun);
        assertEquals("[\"mvn test\",\"./mvnw test\"]", afterSecondRun);
    }

    @Test
    void migrationRollsBackAllChangesWhenARowFailsPartway() throws SQLException {
        // A trigger that rejects long accepted_answers values forces the second row's UPDATE to
        // fail partway through the migration, so we can prove the whole migration is atomic: the
        // first row's already-applied update must roll back too, and no migration is recorded.
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_long_answers BEFORE UPDATE ON flashcards
                    WHEN length(NEW.accepted_answers) > 30
                    BEGIN SELECT RAISE(ABORT, 'accepted_answers too long'); END
                    """);
        }

        long shortRowId = insertCard("RECALL", "when|Mockito.when");
        long longRowId = insertCard("RECALL", "@ControllerAdvice|@RestControllerAdvice");

        assertThrows(SQLException.class, () -> SchemaMigrator.migrate(connection));

        assertEquals("when|Mockito.when", acceptedAnswers(shortRowId));
        assertEquals("@ControllerAdvice|@RestControllerAdvice", acceptedAnswers(longRowId));

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1));
        }
    }

    @Test
    void recordsAppliedMigrationVersion() throws SQLException {
        SchemaMigrator.migrate(connection);

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version >= 1")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getInt(1) >= 1);
        }
    }

    @Test
    void backfillsPreviouslyReviewedCardsToReviewState() throws SQLException {
        long reviewedCardId = insertCard("RECALL", "extends", 5);
        long neverReviewedCardId = insertCard("RECALL", "extends", 0);

        SchemaMigrator.migrate(connection);

        assertEquals("REVIEW", cardState(reviewedCardId));
        assertEquals("NEW", cardState(neverReviewedCardId));
    }

    @Test
    void backfillSetsIntroducedAtFromCreatedAtWhenMissing() throws SQLException {
        long id = insertCard("RECALL", "extends", 3);

        SchemaMigrator.migrate(connection);

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT introduced_at FROM flashcards WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals("2026-01-01 00:00:00", resultSet.getString(1));
            }
        }
    }

    @Test
    void lifecycleBackfillIsIdempotent() throws SQLException {
        long id = insertCard("RECALL", "extends", 2);

        SchemaMigrator.migrate(connection);
        String afterFirstRun = cardState(id);
        SchemaMigrator.migrate(connection);
        String afterSecondRun = cardState(id);

        assertEquals("REVIEW", afterFirstRun);
        assertEquals(afterFirstRun, afterSecondRun);
    }

    private long insertCard(String cardType, String acceptedAnswers) throws SQLException {
        return insertCard(cardType, acceptedAnswers, 0);
    }

    private long insertCard(String cardType, String acceptedAnswers, int reviewCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO flashcards (card_type, accepted_answers, review_count) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, cardType);
            statement.setString(2, acceptedAnswers);
            statement.setInt(3, reviewCount);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private String acceptedAnswers(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT accepted_answers FROM flashcards WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private String cardState(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT card_state FROM flashcards WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }
}
