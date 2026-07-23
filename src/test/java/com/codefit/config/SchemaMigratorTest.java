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
                        accepted_answers TEXT
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
    void recordsAppliedMigrationVersion() throws SQLException {
        SchemaMigrator.migrate(connection);

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    private long insertCard(String cardType, String acceptedAnswers) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO flashcards (card_type, accepted_answers) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, cardType);
            statement.setString(2, acceptedAnswers);
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
}
