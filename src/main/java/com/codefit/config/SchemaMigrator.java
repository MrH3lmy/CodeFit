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
     * Converts accepted_answers values that still use the legacy "|" delimiter into the
     * {@link AcceptedAnswerCodec} structured format. REGEX_PATTERN cards are skipped entirely
     * because "|" is meaningful regex syntax there, not an alternative-answer delimiter.
     */
    private static void migrateLegacyAcceptedAnswers(Connection connection) throws SQLException {
        record LegacyRow(long id, String acceptedAnswers) {
        }

        List<LegacyRow> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, accepted_answers FROM flashcards WHERE card_type <> ? AND accepted_answers LIKE '%|%'")) {
            select.setString(1, CardType.REGEX_PATTERN.name());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new LegacyRow(resultSet.getLong("id"), resultSet.getString("accepted_answers")));
                }
            }
        }

        if (rows.isEmpty()) {
            return;
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE flashcards SET accepted_answers = ? WHERE id = ?")) {
            for (LegacyRow row : rows) {
                String migrated = AcceptedAnswerCodec.encode(splitLegacyPipeDelimited(row.acceptedAnswers()));
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

    private static List<String> splitLegacyPipeDelimited(String raw) {
        List<String> answers = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                answers.add(trimmed);
            }
        }
        return answers;
    }
}
