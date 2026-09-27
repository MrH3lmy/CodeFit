package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.sqlite.BusyHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers #192's deep-review finding: {@link Transactions#run} must never let work reach the database
 * after reporting failure, even when the failure is the explicit {@code COMMIT} itself (not just an
 * exception from the work being committed). The original implementation only caught
 * {@link RuntimeException} around {@code commit()}; a {@link SQLException} from a failed commit skipped
 * rollback and fell into a {@code finally} that restored {@code autoCommit(true)} — which, per the JDBC
 * contract, implicitly commits a still-open transaction. This reproduces that with a real SQLite
 * {@code SQLITE_BUSY} on the explicit commit, using a second connection to hold the read lock.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class TransactionsTest {

    @BeforeEach
    void createProbeTable() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS transactions_test_probe (value INTEGER)");
            statement.execute("DELETE FROM transactions_test_probe");
            statement.execute("INSERT INTO transactions_test_probe VALUES (0)");
        }
    }

    @Test
    void aFailedCommitIsRolledBackAndNeverSilentlyAppliedByRestoringAutoCommit() throws Exception {
        try (Connection reader = DatabaseConfig.getConnection()) {
            reader.setAutoCommit(false);
            try (Statement readStatement = reader.createStatement();
                 var resultSet = readStatement.executeQuery("SELECT value FROM transactions_test_probe")) {
                assertTrue(resultSet.next());
            }

            assertThrows(IllegalStateException.class, () -> Transactions.run(writer -> {
                try {
                    // Fires when the writer's own explicit COMMIT collides with the reader's lock.
                    // Frees that lock inside the callback (so a wrongly-issued second commit during
                    // cleanup would succeed) and returns 0 so SQLite fails the busy wait immediately
                    // instead of retrying it away.
                    BusyHandler.setHandler(writer, new BusyHandler() {
                        @Override
                        protected int callback(int attempts) throws SQLException {
                            reader.rollback();
                            return 0;
                        }
                    });
                    try (Statement writeStatement = writer.createStatement()) {
                        writeStatement.executeUpdate("UPDATE transactions_test_probe SET value = 1");
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }

        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT value FROM transactions_test_probe")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1),
                    "the update was committed even though Transactions.run reported failure");
        }
    }
}
