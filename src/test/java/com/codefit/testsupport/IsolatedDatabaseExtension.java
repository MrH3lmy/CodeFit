package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Points every {@link DatabaseConfig#getConnection()} call the annotated test class makes at a fresh,
 * throwaway SQLite file instead of the shared local {@code codefit.db} (#175). The temp directory is
 * created and removed by the extension so migrated test classes only need the extension and resource
 * lock annotations instead of repeating database lifecycle boilerplate.
 *
 * <p>The extension always restores {@link DatabaseConfig#useDefaultDatabaseFile()} after the class.
 * It also performs the same restoration and cleanup immediately if schema initialization fails, so a
 * failed {@code beforeAll} can never leak the process-wide test database URL into later test classes.
 *
 * <p>Usage: {@code @ExtendWith(IsolatedDatabaseExtension.class)} plus
 * {@code @ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)} on the test class. The resource
 * lock serializes all database-backed test classes if JUnit parallel execution is enabled later. The
 * current suite also explicitly disables parallel execution in {@code junit-platform.properties}.
 *
 * <p>Before redirecting the URL, the extension fingerprints the repository-root {@code codefit.db}.
 * Teardown verifies the file has exactly the same existence, type, size, modification time, and file
 * identity afterward. This protects a developer's existing database instead of requiring them to
 * delete it before running tests, while still detecting accidental creation, replacement, deletion,
 * or mutation by a test that escapes its isolated database.
 */
public final class IsolatedDatabaseExtension implements BeforeAllCallback, AfterAllCallback {

    /** Shared {@code @ResourceLock} key naming {@link DatabaseConfig}'s global static {@code databaseUrl}. */
    public static final String DATABASE_RESOURCE = "com.codefit.config.DatabaseConfig.databaseUrl";

    /** The repository-root production database path that tests must leave unchanged. */
    static final Path DEFAULT_DATABASE_PATH = Path.of("codefit.db").toAbsolutePath().normalize();

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(IsolatedDatabaseExtension.class);
    private static final String TEMP_DIR_KEY = "tempDir";
    private static final String DEFAULT_DATABASE_STATE_KEY = "defaultDatabaseState";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(DEFAULT_DATABASE_STATE_KEY, DefaultDatabaseState.capture(DEFAULT_DATABASE_PATH));

        Path tempDir = Files.createTempDirectory("codefit-isolated-db-");
        store.put(TEMP_DIR_KEY, tempDir);
        DatabaseConfig.useDatabaseFile(tempDir.resolve("test.db"));

        try {
            DatabaseConfig.initialize();
        } catch (Exception failure) {
            cleanupAfterSetupFailure(context, failure);
            throw failure;
        } catch (Error failure) {
            cleanupAfterSetupFailure(context, failure);
            throw failure;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        cleanup(context);
    }

    private static void cleanupAfterSetupFailure(ExtensionContext context, Throwable setupFailure) {
        try {
            cleanup(context);
        } catch (Exception | Error cleanupFailure) {
            setupFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void cleanup(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        DatabaseConfig.useDefaultDatabaseFile();

        Throwable failure = null;
        Path tempDir = store.remove(TEMP_DIR_KEY, Path.class);
        try {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        } catch (Exception | Error cleanupFailure) {
            failure = cleanupFailure;
        }

        DefaultDatabaseState baseline = store.remove(DEFAULT_DATABASE_STATE_KEY, DefaultDatabaseState.class);
        try {
            if (baseline != null) {
                baseline.assertUnchanged(DEFAULT_DATABASE_PATH, context.getRequiredTestClass().getName());
            }
        } catch (Exception | Error verificationFailure) {
            if (failure == null) {
                failure = verificationFailure;
            } else {
                failure.addSuppressed(verificationFailure);
            }
        }

        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walkedPaths = Files.walk(root)) {
            paths = walkedPaths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /** Lightweight metadata fingerprint that avoids reading or hashing a developer's database. */
    static record DefaultDatabaseState(
            boolean exists,
            boolean regularFile,
            long size,
            FileTime lastModifiedTime,
            String fileKey) {

        static DefaultDatabaseState capture(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new DefaultDatabaseState(false, false, 0L, null, null);
            }

            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new DefaultDatabaseState(
                    true,
                    attributes.isRegularFile(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    String.valueOf(attributes.fileKey()));
        }

        void assertUnchanged(Path path, String testClassName) throws IOException {
            DefaultDatabaseState current = capture(path);
            if (!equals(current)) {
                throw new AssertionError("The repository-root database " + path
                        + " changed while " + testClassName + " was running. Expected " + this
                        + " but found " + current + ". Every repository/service integration test must "
                        + "use " + IsolatedDatabaseExtension.class.getSimpleName()
                        + " and leave an existing developer database untouched (issue #175).");
            }
        }
    }
}
