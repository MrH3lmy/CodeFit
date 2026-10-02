package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@link IsolatedDatabaseExtension} isolates the database once per test <em>class</em>
 * ({@code @BeforeAllCallback}/{@code @AfterAllCallback}), so several {@code @Test} methods in one
 * class share the same on-disk database. #181's identity and profile tables are singletons
 * ({@code CHECK (id = 1)}), so a second test method's {@code createIdentity}/{@code editProfile} call
 * would otherwise collide with the first test's row. Call {@link #resetAll()} from a
 * {@code @BeforeEach} to give every test method a clean slate within the shared isolated database.
 */
public final class PeerIdentityTestTables {
    private PeerIdentityTestTables() {
    }

    public static void resetAll() {
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM consent_change_events");
            statement.execute("DELETE FROM contact_permissions");
            statement.execute("DELETE FROM contacts");
            statement.execute("DELETE FROM identity_key_rotations");
            statement.execute("DELETE FROM social_profile");
            statement.execute("DELETE FROM peer_identity");
            statement.execute("DELETE FROM transport_identity");
            statement.execute("DELETE FROM local_progress_snapshots");
            statement.execute("DELETE FROM local_preparation_checkpoints");
            statement.execute("DELETE FROM preparation_snapshot_wire_state");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to reset peer identity test tables", exception);
        }
    }
}
