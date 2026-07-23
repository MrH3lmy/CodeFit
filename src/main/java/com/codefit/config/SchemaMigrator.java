package com.codefit.config;

import com.codefit.model.CardType;
import com.codefit.service.AcceptedAnswerCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies versioned, idempotent content migrations to an existing CodeFit database. Each
 * migration runs at most once, tracked in {@code schema_migrations}, and is applied in its own
 * transaction so a failure rolls back cleanly without leaving partially converted data.
 */
final class SchemaMigrator {

    private SchemaMigrator() {
    }

    @FunctionalInterface
    private interface Migration {
        void apply(Connection connection) throws SQLException;
    }

    private record VersionedMigration(int version, String description, Migration migration) {
    }

    private static final List<VersionedMigration> MIGRATIONS = List.of(
            new VersionedMigration(1,
                    "Convert legacy pipe-delimited accepted answers to the structured codec format",
                    SchemaMigrator::migrateLegacyAcceptedAnswers),
            new VersionedMigration(2,
                    "Backfill card lifecycle state for cards created before card_state existed",
                    SchemaMigrator::backfillCardLifecycleState)
    );

    static void migrate(Connection connection) throws SQLException {
        ensureMigrationsTable(connection);
        int appliedVersion = currentVersion(connection);
        for (VersionedMigration migration : MIGRATIONS) {
            if (migration.version() <= appliedVersion) {
                continue;
            }
            applyMigration(connection, migration);
        }
    }

    private static void applyMigration(Connection connection, VersionedMigration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            migration.migration().apply(connection);
            recordMigration(connection, migration.version(), migration.description());
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw new SQLException("Migration " + migration.version() + " (" + migration.description() + ") failed", exception);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void ensureMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description TEXT,
                        applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void recordMigration(Connection connection, int version, String description) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (version, description) VALUES (?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, description);
            statement.executeUpdate();
        }
    }

    /**
     * The pre-#90 seeded starter deck used "|" as an ad hoc alternate-answer delimiter for
     * exactly these fifteen values. A blind "split every pipe-containing, non-regex value"
     * migration is unsafe: real accepted answers legitimately contain "|", e.g. a Linux pipeline
     * ({@code ps aux | grep java}) or SQL concatenation ({@code first_name || ' ' || last_name}).
     * Splitting those would corrupt them. Instead, only rows whose accepted_answers value is an
     * EXACT match for one of these known legacy strings are migrated; every other pipe-containing
     * value (regardless of card type) is left untouched.
     */
    private static final Map<String, List<String>> KNOWN_LEGACY_PIPE_ANSWERS = Map.ofEntries(
            Map.entry("PreparedStatement prevents SQL injection by binding parameters|It uses bind parameters instead of concatenating user input",
                    List.of("PreparedStatement prevents SQL injection by binding parameters", "It uses bind parameters instead of concatenating user input")),
            Map.entry("SELECT email FROM users ORDER BY created_at DESC LIMIT 5;|SELECT email FROM users ORDER BY created_at DESC LIMIT 5",
                    List.of("SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5")),
            Map.entry("unit of work that commits or rolls back|all-or-nothing unit of work",
                    List.of("unit of work that commits or rolls back", "all-or-nothing unit of work")),
            Map.entry("201 Created|201", List.of("201 Created", "201")),
            Map.entry("DTOs are API contracts and entities are persistence models|DTO for request response, entity for database domain",
                    List.of("DTOs are API contracts and entities are persistence models", "DTO for request response, entity for database domain")),
            Map.entry("explicit dependencies final fields fail fast|required dependencies are explicit and immutable",
                    List.of("explicit dependencies final fields fail fast", "required dependencies are explicit and immutable")),
            Map.entry("persistent entity mapped to a database table|class mapped to a database table",
                    List.of("persistent entity mapped to a database table", "class mapped to a database table")),
            Map.entry("unit isolates code, integration tests components together|unit test mocks dependencies integration test uses real components",
                    List.of("unit isolates code, integration tests components together", "unit test mocks dependencies integration test uses real components")),
            Map.entry("when|Mockito.when", List.of("when", "Mockito.when")),
            Map.entry("sessions are server-side and revocable, JWTs are stateless but harder to revoke|JWT stateless session server state",
                    List.of("sessions are server-side and revocable, JWTs are stateless but harder to revoke", "JWT stateless session server state")),
            Map.entry("mvn test|./mvnw test", List.of("mvn test", "./mvnw test")),
            Map.entry("mvn clean package|./mvnw clean package", List.of("mvn clean package", "./mvnw clean package")),
            Map.entry("java -jar app.jar --spring.profiles.active=prod|SPRING_PROFILES_ACTIVE=prod java -jar app.jar",
                    List.of("java -jar app.jar --spring.profiles.active=prod", "SPRING_PROFILES_ACTIVE=prod java -jar app.jar")),
            Map.entry("prevents committing secrets and supports per-environment config|keeps credentials out of source control",
                    List.of("prevents committing secrets and supports per-environment config", "keeps credentials out of source control")),
            Map.entry("@ControllerAdvice|@RestControllerAdvice", List.of("@ControllerAdvice", "@RestControllerAdvice"))
    );

    private static void migrateLegacyAcceptedAnswers(Connection connection) throws SQLException {
        record LegacyRow(long id, String acceptedAnswers) {
        }

        List<LegacyRow> candidates = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, accepted_answers FROM flashcards WHERE card_type <> ? AND accepted_answers LIKE '%|%' ORDER BY id")) {
            select.setString(1, CardType.REGEX_PATTERN.name());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new LegacyRow(resultSet.getLong("id"), resultSet.getString("accepted_answers")));
                }
            }
        }

        List<LegacyRow> knownLegacyRows = candidates.stream()
                .filter(row -> KNOWN_LEGACY_PIPE_ANSWERS.containsKey(row.acceptedAnswers()))
                .toList();

        if (knownLegacyRows.isEmpty()) {
            return;
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE flashcards SET accepted_answers = ? WHERE id = ?")) {
            for (LegacyRow row : knownLegacyRows) {
                String migrated = AcceptedAnswerCodec.encode(KNOWN_LEGACY_PIPE_ANSWERS.get(row.acceptedAnswers()));
                update.setString(1, migrated);
                update.setLong(2, row.id());
                update.executeUpdate();
            }
        }
    }

    /**
     * The card_state/introduced_at columns default new rows to NEW, which is wrong for cards
     * that already had reviews before lifecycle states existed. Move those into REVIEW so they
     * don't count against the daily new-card limit, using their creation date as a best-effort
     * introduced_at since the real introduction date wasn't recorded.
     */
    private static void backfillCardLifecycleState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE flashcards
                    SET card_state = 'REVIEW',
                        introduced_at = COALESCE(introduced_at, created_at)
                    WHERE review_count > 0 AND card_state = 'NEW'
                    """);
        }
    }

}
