package com.codefit.config;

import com.codefit.model.CardType;
import com.codefit.service.AcceptedAnswerCodec;
import com.codefit.service.SqlCardSpec;
import com.codefit.service.SqlCardSpecCodec;

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
                    SchemaMigrator::backfillCardLifecycleState),
            new VersionedMigration(3,
                    "Convert the seeded newest-user-emails SQL_QUERY card to fixture-based grading config",
                    SchemaMigrator::migrateSeededSqlQueryCard),
            new VersionedMigration(4,
                    "Add local peer identity, social profile, and key rotation history tables (#181)",
                    SchemaMigrator::createPeerIdentityTables),
            new VersionedMigration(5,
                    "Add peer contacts, sharing permissions, and consent-change outbox tables (#181)",
                    SchemaMigrator::createPeerContactTables)
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
     * SQL_QUERY cards used to be graded by text-matching a saved answer string; the seeded
     * "newest user emails" starter card stored its accepted answers this way, either as the
     * original pre-#90 pipe-delimited string or (after migration 1) the equivalent JSON array.
     * SQL_QUERY grading now executes the attempt against a fixture instead (see
     * {@link SqlCardSpecCodec}), so any install that already seeded this exact starter card needs
     * its accepted_answers value converted to the new fixture-based configuration. Only this exact
     * known legacy value is matched, the same way {@link #KNOWN_LEGACY_PIPE_ANSWERS} avoids
     * touching user-authored cards that merely look similar.
     */
    private static final String LEGACY_NEWEST_EMAILS_ANSWERS = AcceptedAnswerCodec.encode(
            List.of("SELECT email FROM users ORDER BY created_at DESC LIMIT 5;",
                    "SELECT email FROM users ORDER BY created_at DESC LIMIT 5"));

    private static final SqlCardSpec NEWEST_EMAILS_SQL_SPEC = new SqlCardSpec(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, created_at TEXT NOT NULL);",
            "INSERT INTO users (id, email, created_at) VALUES "
                    + "(1,'ada@example.com','2024-01-01'),(2,'ben@example.com','2024-01-02'),"
                    + "(3,'cleo@example.com','2024-01-03'),(4,'drew@example.com','2024-01-04'),"
                    + "(5,'eva@example.com','2024-01-05'),(6,'finn@example.com','2024-01-06'),"
                    + "(7,'grace@example.com','2024-01-07');",
            "SELECT email FROM users ORDER BY created_at DESC LIMIT 5;",
            null, true, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

    private static void migrateSeededSqlQueryCard(Connection connection) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE flashcards SET accepted_answers = ? WHERE card_type = ? AND accepted_answers = ?")) {
            update.setString(1, SqlCardSpecCodec.encode(NEWEST_EMAILS_SQL_SPEC));
            update.setString(2, CardType.SQL_QUERY.name());
            update.setString(3, LEGACY_NEWEST_EMAILS_ANSWERS);
            update.executeUpdate();
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

    /**
     * #181's local identity, its editable social profile (deliberately separate from
     * {@code com.codefit.model.InterviewPreparationProfile}), and its key-rotation continuity
     * history. {@code peer_identity} and {@code social_profile} are singleton tables
     * ({@code CHECK (id = 1)}, matching {@code user_progress}'s existing single-row convention)
     * because #181 supports exactly one local identity per install. The private key never appears
     * unencrypted: {@code private_key_ciphertext} is AES/GCM output, and only the passphrase used to
     * seal it (never itself persisted) can open it — see {@code com.codefit.peer.identity.crypto}.
     */
    private static void createPeerIdentityTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_identity (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        identity_public_key BLOB NOT NULL,
                        private_key_ciphertext BLOB NOT NULL,
                        private_key_salt BLOB NOT NULL,
                        private_key_iterations INTEGER NOT NULL,
                        private_key_nonce BLOB NOT NULL,
                        key_format_version INTEGER NOT NULL DEFAULT 1,
                        highest_known_epoch INTEGER NOT NULL DEFAULT 0,
                        current_writer_epoch INTEGER NOT NULL DEFAULT 0,
                        sharing_paused INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_restored_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS social_profile (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        display_name TEXT NOT NULL,
                        bio TEXT,
                        comparison_zone_id TEXT NOT NULL DEFAULT 'UTC',
                        week_start INTEGER NOT NULL DEFAULT 1,
                        avatar_bytes BLOB,
                        avatar_mime_type TEXT,
                        profile_revision INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            // Populated only by IdentityService.rotateKeyWithContinuity: a signature by the OLD
            // identity key vouching for the new one, so an existing contact can adopt the new key
            // without re-pairing from scratch once a later issue transmits it (ADR-0001 §1).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS identity_key_rotations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        old_public_key BLOB NOT NULL,
                        new_public_key BLOB NOT NULL,
                        rotated_at TEXT NOT NULL,
                        continuity_signature BLOB NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    /**
     * #181's contacts (pinned by {@code identity_id}, the SHA-256 of the contact's public key, so a
     * pin can never silently move to a different key under the same row), their per-contact sharing
     * grant, and an outbox of grant/revocation changes for #184's sync layer to eventually publish as
     * {@code ConsentRevision} messages while the contact may be offline. {@code contact_permissions}
     * defaults every column to "nothing granted" so a newly paired contact discloses nothing until
     * the learner explicitly grants a scope (#181 acceptance criterion).
     */
    private static void createPeerContactTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        identity_id BLOB NOT NULL UNIQUE,
                        identity_public_key BLOB NOT NULL,
                        fingerprint TEXT NOT NULL,
                        display_name TEXT NOT NULL DEFAULT '',
                        alias TEXT,
                        trust_state TEXT NOT NULL DEFAULT 'PENDING',
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contact_permissions (
                        contact_id INTEGER PRIMARY KEY REFERENCES contacts(id) ON DELETE CASCADE,
                        allowed_scopes TEXT NOT NULL DEFAULT '',
                        historical_window_days INTEGER,
                        expires_at TEXT,
                        allow_forwarding INTEGER NOT NULL DEFAULT 0,
                        revision INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS consent_change_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
                        allowed_scopes TEXT NOT NULL DEFAULT '',
                        request_cache_deletion INTEGER NOT NULL DEFAULT 0,
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        synchronized INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_consent_change_events_contact_id ON consent_change_events(contact_id)");
        }
    }

}
