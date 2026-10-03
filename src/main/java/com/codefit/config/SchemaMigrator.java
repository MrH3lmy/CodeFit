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
                    SchemaMigrator::createPeerContactTables),
            new VersionedMigration(6,
                    "Add the local transport identity table for TLS mutual authentication (#182)",
                    SchemaMigrator::createTransportIdentityTable),
            new VersionedMigration(7,
                    "Add the local reachable-address cache per contact (#182)",
                    SchemaMigrator::createContactAddressTable),
            new VersionedMigration(8,
                    "Add the last-observed transport-key binding per contact, for dial-time pinning (#182)",
                    SchemaMigrator::createContactTransportBindingTable),
            new VersionedMigration(9,
                    "Add local progress/preparation snapshot and checkpoint tables (#183)",
                    SchemaMigrator::createLocalSnapshotTables),
            new VersionedMigration(10,
                    "Add the preparation-snapshot wire revision state, independent of the per-day local "
                            + "checkpoint revision (#183 review fix)",
                    SchemaMigrator::createPreparationSnapshotWireStateTable),
            new VersionedMigration(11,
                    "Add #184 resumable peer sync: persisted per-author replay state, the validated "
                            + "peer inbox cache, and the publication outbox",
                    SchemaMigrator::createPeerSyncTables)
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

    /**
     * #182's local transport key pair and its current {@code IDENTITY_BINDING} validity window: the
     * separate Ed25519 key used in the device's self-signed mutual-TLS certificate (ADR-0001 §1/§5),
     * distinct from the identity key in {@code peer_identity}. A singleton table ({@code CHECK (id = 1)}),
     * matching {@code peer_identity}'s own convention, because #182 supports exactly one active transport
     * key per local identity at a time. The private key is sealed the same way the identity vault seals
     * its own private key (PBKDF2 + AES/GCM, {@code com.codefit.peer.identity.crypto.PassphraseCipher}),
     * under the same vault passphrase, so enabling networking and unlocking the identity are one action.
     */
    private static void createTransportIdentityTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transport_identity (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        transport_public_key BLOB NOT NULL,
                        private_key_ciphertext BLOB NOT NULL,
                        private_key_salt BLOB NOT NULL,
                        private_key_iterations INTEGER NOT NULL,
                        private_key_nonce BLOB NOT NULL,
                        binding_valid_from TEXT NOT NULL,
                        binding_valid_until TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    /**
     * #182's local cache of reachable addresses per contact: seeded from an accepted invitation's
     * IP-literal addresses, extendable with an explicit manual address or an opt-in LAN discovery hit
     * ("Reconnect from a local contact/address cache", #182). {@code UNIQUE(contact_id, host, port)}
     * turns a repeated sighting of the same address into an update of {@code last_seen_at} rather than a
     * duplicate row; {@code ON DELETE CASCADE} matches every other contact-scoped table so removing a
     * contact removes its cached addresses too.
     */
    private static void createContactAddressTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contact_addresses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        added_at TEXT NOT NULL,
                        last_seen_at TEXT NOT NULL,
                        UNIQUE(contact_id, host, port)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_contact_addresses_contact_id ON contact_addresses(contact_id)");
        }
    }

    /**
     * #182's last-observed transport key and {@code IDENTITY_BINDING} validity window per contact: what
     * a future dial pins against (protocol/ADR "pin the expected transport key"). Seeded from an accepted
     * invitation's declared binding, and refreshed whenever a live connection authenticates that contact
     * under a (possibly rotated) transport key. A singleton row per contact ({@code PRIMARY KEY
     * contact_id}), since only the most recent observation is ever useful for pinning the next dial.
     */
    private static void createContactTransportBindingTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contact_transport_bindings (
                        contact_id INTEGER PRIMARY KEY REFERENCES contacts(id) ON DELETE CASCADE,
                        transport_public_key BLOB NOT NULL,
                        valid_from TEXT NOT NULL,
                        valid_until TEXT NOT NULL,
                        observed_at TEXT NOT NULL
                    )
                    """);
        }
    }

    /**
     * #183's own, locally generated snapshot/checkpoint storage — deliberately named and namespaced
     * apart from any future #184 "received/cached peer snapshot" tables, which must never share this
     * prefix. A progress snapshot is keyed by the local window it describes ({@code window_kind},
     * {@code local_start_epoch_day}, {@code zone_id}, {@code week_start}): capturing the same window
     * again is a <em>correction</em> that replaces this row (the service layer enforces the revision
     * can only move forward), not a second additive total. A preparation checkpoint is keyed by
     * {@code (profile_id, checkpoint_date)} (a UTC calendar day): at most one checkpoint per profile
     * per day, so a same-day re-capture corrects it and a different day is a genuinely new, permanent
     * historical row — never replaced by a later capture. Metrics/domains are child rows rather than
     * an encoded blob so each value stays individually queryable and typed.
     */
    private static void createLocalSnapshotTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS local_progress_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        window_kind TEXT NOT NULL,
                        local_start_epoch_day INTEGER NOT NULL,
                        zone_id TEXT NOT NULL,
                        week_start INTEGER,
                        window_start TEXT NOT NULL,
                        window_end TEXT NOT NULL,
                        cutoff TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        captured_at TEXT NOT NULL
                    )
                    """);
            // A plain UNIQUE(...) table constraint treats every NULL week_start (every DAY window) as
            // distinct from every other, since SQL NULLs never compare equal to each other - so a DAY
            // window's identity would never be enforced as unique at the database level. COALESCE makes
            // the index itself NULL-safe; the repository's own lookup is independently NULL-safe too.
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_local_progress_snapshots_window "
                    + "ON local_progress_snapshots(window_kind, local_start_epoch_day, zone_id, COALESCE(week_start, -1))");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS local_progress_snapshot_metrics (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        snapshot_id INTEGER NOT NULL REFERENCES local_progress_snapshots(id) ON DELETE CASCADE,
                        metric_order INTEGER NOT NULL,
                        metric_id TEXT NOT NULL,
                        metric_version INTEGER NOT NULL,
                        unit TEXT NOT NULL,
                        availability TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        sample_size INTEGER NOT NULL,
                        provenance TEXT NOT NULL,
                        timestamp_basis TEXT NOT NULL,
                        UNIQUE(snapshot_id, metric_id, metric_version)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS local_preparation_checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        profile_id TEXT NOT NULL,
                        checkpoint_date TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        profile_fingerprint BLOB NOT NULL,
                        scoring_version INTEGER NOT NULL,
                        overall_threshold_percent INTEGER NOT NULL,
                        captured_at TEXT NOT NULL,
                        overall_percent INTEGER,
                        coverage_percent INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        UNIQUE(profile_id, checkpoint_date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS local_preparation_checkpoint_domains (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        checkpoint_id INTEGER NOT NULL REFERENCES local_preparation_checkpoints(id) ON DELETE CASCADE,
                        domain_order INTEGER NOT NULL,
                        domain_id TEXT NOT NULL,
                        weight_percent INTEGER NOT NULL,
                        critical_gate INTEGER NOT NULL,
                        threshold_percent INTEGER,
                        score_percent INTEGER,
                        coverage_percent INTEGER NOT NULL,
                        measured_requirement_count INTEGER NOT NULL,
                        total_requirement_count INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        UNIQUE(checkpoint_id, domain_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_local_progress_snapshot_metrics_snapshot "
                    + "ON local_progress_snapshot_metrics(snapshot_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_local_preparation_checkpoints_profile "
                    + "ON local_preparation_checkpoints(profile_id, checkpoint_date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_local_preparation_checkpoint_domains_checkpoint "
                    + "ON local_preparation_checkpoint_domains(checkpoint_id)");
        }
    }

    /**
     * The wire-facing {@code PreparationSnapshot} object is keyed only by (author, recipient, profile
     * id) — an evolving "this profile's latest known readiness" stream, the same object across every
     * day it is ever published for. That is a <em>different</em> logical object from
     * {@code local_preparation_checkpoints}, which is keyed per UTC day for the learner's own local
     * historical trend. Reusing the per-day local checkpoint's own revision as the wire revision would
     * collide: two different days' first-ever local capture would each independently start at revision
     * 1, so the second day's publish would be rejected by a receiver as a stale/duplicate revision of
     * the first day's. This table gives the wire object its own revision counter, strictly increasing
     * per {@code object_id} regardless of which local day produced the content, and lets an unchanged
     * resend (identical {@code body_fingerprint}) reuse the same revision rather than bumping it.
     */
    private static void createPreparationSnapshotWireStateTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS preparation_snapshot_wire_state (
                        object_id BLOB PRIMARY KEY,
                        revision INTEGER NOT NULL,
                        body_fingerprint BLOB NOT NULL
                    )
                    """);
        }
    }

    /**
     * #184: resumable peer synchronization. Three distinct concerns, each its own table family, none
     * sharing a name prefix with {@code local_*} (this device's own evidence) so the two can never be
     * confused (see {@code createLocalSnapshotTables}'s own warning):
     *
     * <ul>
     *   <li><b>Persisted replay state</b> ({@code peer_sync_*}) — the durable counterpart of
     *       {@code com.codefit.peer.protocol.AuthorReplayState}, which protocol-v1.md §10 and that
     *       class's own javadoc explicitly declare in-memory-only and {@code #184}'s to persist: the
     *       highest epoch accepted per remote author, every accepted message id/{@code (epoch,
     *       sequence)} slot (duplicate/fork detection), and the latest {@code (epoch, revision,
     *       isTombstone)} per {@code (author, objectId)} (stale-revision/anti-resurrection). Also the
     *       receiver-side cache of each author's latest accepted {@code CONSENT_REVISION} scopes,
     *       which §10 says the sync layer owns and must check before trusting a body's required scope.
     *       {@code peer_sync_object_versions} is never deleted for a tombstoned object before its
     *       retention window elapses (see {@code PeerSyncRetention}) — that row is the only thing
     *       standing between a replayed pre-revocation copy and resurrection.</li>
     *   <li><b>Validated peer inbox</b> ({@code peer_progress_summaries}/{@code
     *       peer_preparation_snapshots}/{@code peer_social_profile_cards}) — cached remote content that
     *       passed every check, holding only the latest revision per {@code (author, objectId)}
     *       (supersession, not an append log). Deliberately a completely separate table family from
     *       this device's own {@code local_progress_snapshots}/{@code local_preparation_checkpoints}:
     *       receiving a peer's result must never be mistaken for, or alter, this learner's own
     *       attempts, reviews, XP, mastery, or readiness.</li>
     *   <li><b>Publication outbox</b> ({@code publication_outbox}) — the logical objects a learner has
     *       explicitly approved for ongoing sharing with one contact. Sharing is never automatic from
     *       evidence/grants alone; an outbox row is the record of that explicit approval. {@code
     *       last_synced_revision}/{@code last_synced_at} are optimistic, UI-facing hints only (updated
     *       after a full clean bidirectional session, never merely after a local socket write - see
     *       {@code PeerSyncService}) and are never consulted to decide what to (re)send, so an
     *       over-optimistic hint can delay a UI update but can never lose data: resend is always the
     *       full current eligible set, and the receiver's own replay state makes redelivery harmless.</li>
     * </ul>
     */
    private static void createPeerSyncTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_sync_author_state (
                        author_identity_id BLOB PRIMARY KEY,
                        highest_epoch INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_sync_accepted_messages (
                        author_identity_id BLOB NOT NULL,
                        message_id BLOB NOT NULL,
                        epoch INTEGER NOT NULL,
                        sequence INTEGER NOT NULL,
                        expires_at TEXT NOT NULL,
                        PRIMARY KEY (author_identity_id, message_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_peer_sync_accepted_messages_slot "
                    + "ON peer_sync_accepted_messages(author_identity_id, epoch, sequence)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_peer_sync_accepted_messages_expiry "
                    + "ON peer_sync_accepted_messages(expires_at)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_sync_object_versions (
                        author_identity_id BLOB NOT NULL,
                        object_id BLOB NOT NULL,
                        epoch INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        is_tombstone INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (author_identity_id, object_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_sync_consent (
                        author_identity_id BLOB PRIMARY KEY,
                        allowed_scopes TEXT NOT NULL,
                        epoch INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_progress_summaries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        author_identity_id BLOB NOT NULL,
                        object_id BLOB NOT NULL,
                        epoch INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        window_kind TEXT NOT NULL,
                        local_start_epoch_day INTEGER NOT NULL,
                        zone_id TEXT NOT NULL,
                        week_start INTEGER,
                        window_start TEXT NOT NULL,
                        window_end TEXT NOT NULL,
                        cutoff TEXT NOT NULL,
                        received_at TEXT NOT NULL,
                        UNIQUE(author_identity_id, object_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_progress_summary_metrics (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        summary_id INTEGER NOT NULL REFERENCES peer_progress_summaries(id) ON DELETE CASCADE,
                        metric_order INTEGER NOT NULL,
                        metric_id TEXT NOT NULL,
                        metric_version INTEGER NOT NULL,
                        unit TEXT NOT NULL,
                        availability TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        sample_size INTEGER NOT NULL,
                        provenance TEXT NOT NULL,
                        timestamp_basis TEXT NOT NULL,
                        UNIQUE(summary_id, metric_id, metric_version)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_preparation_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        author_identity_id BLOB NOT NULL,
                        object_id BLOB NOT NULL,
                        epoch INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        profile_id TEXT NOT NULL,
                        profile_fingerprint BLOB NOT NULL,
                        scoring_version INTEGER NOT NULL,
                        overall_threshold_percent INTEGER NOT NULL,
                        captured_at TEXT NOT NULL,
                        overall_percent INTEGER,
                        coverage_percent INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        received_at TEXT NOT NULL,
                        UNIQUE(author_identity_id, object_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_preparation_snapshot_domains (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        snapshot_id INTEGER NOT NULL REFERENCES peer_preparation_snapshots(id) ON DELETE CASCADE,
                        domain_order INTEGER NOT NULL,
                        domain_id TEXT NOT NULL,
                        weight_percent INTEGER NOT NULL,
                        critical_gate INTEGER NOT NULL,
                        threshold_percent INTEGER,
                        score_percent INTEGER,
                        coverage_percent INTEGER NOT NULL,
                        measured_requirement_count INTEGER NOT NULL,
                        total_requirement_count INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        UNIQUE(snapshot_id, domain_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS peer_social_profile_cards (
                        author_identity_id BLOB PRIMARY KEY,
                        object_id BLOB NOT NULL,
                        epoch INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        display_name TEXT NOT NULL,
                        bio TEXT,
                        comparison_zone_id TEXT NOT NULL,
                        week_start INTEGER NOT NULL,
                        received_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS publication_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
                        message_type TEXT NOT NULL,
                        logical_key TEXT NOT NULL,
                        approved_at TEXT NOT NULL,
                        last_synced_revision INTEGER,
                        last_synced_at TEXT,
                        UNIQUE(contact_id, message_type, logical_key)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_publication_outbox_contact "
                    + "ON publication_outbox(contact_id)");
        }
    }

}
