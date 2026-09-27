package com.codefit.service;

import com.codefit.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Runs a unit of work on one dedicated JDBC connection with manual commit, so a composite write
 * spanning more than one repository call either all applies or none does. Mirrors
 * {@code com.codefit.config.SchemaMigrator}'s own transaction-per-migration pattern. Repository calls
 * made with the supplied connection (the {@code Connection}-accepting overloads) must not manage
 * commit/rollback themselves — that is this method's job.
 *
 * <p>{@link DatabaseConfig#getConnection()} always opens a brand-new physical connection (no pooling),
 * so this connection is never reused by another caller: on any failure — including a failed
 * {@code commit()} itself, not only an exception from {@code work} — this method rolls back and lets
 * the connection close without ever restoring {@code autoCommit}. Restoring it would call
 * {@code setAutoCommit(true)} on a connection whose transaction might still be open (e.g. a
 * {@code commit()} that failed with {@code SQLITE_BUSY} but left the write pending), and per the JDBC
 * contract that implicitly commits whatever is pending — silently persisting work this method just
 * reported as failed. Skipping that restore on every path but success closes that gap.
 */
final class Transactions {
    private Transactions() {
    }

    static void run(Consumer<Connection> work) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.accept(connection);
                connection.commit();
            } catch (RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure;
            } catch (SQLException commitFailure) {
                IllegalStateException wrapped = new IllegalStateException("Transaction commit failed", commitFailure);
                rollbackQuietly(connection, wrapped);
                throw wrapped;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Transaction failed", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
