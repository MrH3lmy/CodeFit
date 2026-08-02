package com.codefit.testsupport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Explicit, discoverable regression coverage for #175's core requirement: running this suite must
 * never create or touch the repository-root {@code codefit.db}. Passing this test at any point during
 * a run proves the suite hasn't touched it up to that point; the deeper, order-independent guarantee -
 * that no test class anywhere in the run ever leaves it behind - is enforced continuously by
 * {@link IsolatedDatabaseExtension#afterAll} re-asserting the same thing after every single migrated
 * test class's teardown (see that class's docs), not just once here.
 */
class DatabaseIsolationRegressionTest {

    @Test
    void theSharedDefaultDatabaseFileDoesNotExist() {
        assertFalse(Files.exists(IsolatedDatabaseExtension.DEFAULT_DATABASE_PATH),
                "the repository-root " + IsolatedDatabaseExtension.DEFAULT_DATABASE_PATH.toAbsolutePath()
                        + " must never be created by the test suite (#175)");
    }
}
