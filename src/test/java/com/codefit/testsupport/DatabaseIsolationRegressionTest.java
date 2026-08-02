package com.codefit.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused regression coverage for #175's database-file guard. The extension must allow a developer's
 * pre-existing {@code codefit.db} to remain in place unchanged, while detecting accidental creation
 * or mutation of the default file by a test that escapes its isolated database.
 */
class DatabaseIsolationRegressionTest {

    @Test
    void anUnchangedPreExistingDatabaseIsAllowed(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("codefit.db");
        Files.writeString(database, "existing developer data");
        IsolatedDatabaseExtension.DefaultDatabaseState baseline =
                IsolatedDatabaseExtension.DefaultDatabaseState.capture(database);

        assertDoesNotThrow(() -> baseline.assertUnchanged(database, getClass().getName()));
    }

    @Test
    void creatingAPreviouslyAbsentDatabaseIsDetected(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("codefit.db");
        IsolatedDatabaseExtension.DefaultDatabaseState baseline =
                IsolatedDatabaseExtension.DefaultDatabaseState.capture(database);

        Files.writeString(database, "unexpected test database");

        assertThrows(AssertionError.class,
                () -> baseline.assertUnchanged(database, getClass().getName()));
    }

    @Test
    void mutatingAPreExistingDatabaseIsDetected(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("codefit.db");
        Files.writeString(database, "original");
        IsolatedDatabaseExtension.DefaultDatabaseState baseline =
                IsolatedDatabaseExtension.DefaultDatabaseState.capture(database);

        Files.writeString(database, "changed and longer");

        assertThrows(AssertionError.class,
                () -> baseline.assertUnchanged(database, getClass().getName()));
    }
}
