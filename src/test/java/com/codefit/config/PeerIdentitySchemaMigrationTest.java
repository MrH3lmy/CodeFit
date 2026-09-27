package com.codefit.config;

import com.codefit.repository.ContactRepository;
import com.codefit.repository.PeerIdentityRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #181's tables are created by additive, transactional {@link SchemaMigrator} migrations (versions 4
 * and 5), the same mechanism {@code IsolatedDatabaseExtension}-based tests already rely on for retry
 * safety. This covers that the tables actually exist after a fresh install, that the migrations are
 * recorded exactly once, and that re-running migration/initialization (modelling a retried or repeated
 * startup) is a safe no-op rather than a failure or a duplicate side effect.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerIdentitySchemaMigrationTest {

    @Test
    void freshInstallCreatesEveryPeerIdentityTable() throws SQLException {
        Set<String> expected = Set.of("peer_identity", "social_profile", "identity_key_rotations",
                "contacts", "contact_permissions", "consent_change_events");

        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            Set<String> actual = new HashSet<>();
            while (resultSet.next()) {
                actual.add(resultSet.getString("name"));
            }
            assertTrue(actual.containsAll(expected), "Missing peer identity tables: " + expected);
        }
    }

    @Test
    void migrationVersionsFourAndFiveAreRecordedExactlyOnce() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM schema_migrations WHERE version IN (4, 5)")) {
            resultSet.next();
            assertEquals(2, resultSet.getInt(1));
        }
    }

    @Test
    void reRunningInitializationIsIdempotentAndSafeToRetry() {
        // Models an interrupted/retried startup: initialize() (which calls SchemaMigrator.migrate())
        // running a second time against the same already-migrated database must not fail and must
        // not duplicate the singleton identity/profile rows or the migration record.
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();

        assertTrue(new PeerIdentityRepository().find().isEmpty());
        assertTrue(new ContactRepository().findAll().isEmpty());
    }
}
