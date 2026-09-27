package com.codefit.service;

import com.codefit.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Runs a unit of work on one JDBC connection with manual commit, so a composite write spanning more
 * than one repository call either all applies or none does. Mirrors
 * {@code com.codefit.config.SchemaMigrator}'s own transaction-per-migration pattern. Repository calls
 * made with the supplied connection (the {@code Connection}-accepting overloads) must not manage
 * commit/rollback themselves — that is this method's job.
 */
final class Transactions {
    private Transactions() {
    }

    static void run(Consumer<Connection> work) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.accept(connection);
                connection.commit();
            } catch (RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Transaction failed", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, RuntimeException cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
