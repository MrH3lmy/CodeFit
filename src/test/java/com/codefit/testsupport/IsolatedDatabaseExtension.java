package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Points every {@link DatabaseConfig#getConnection()} call the annotated test class makes at a fresh,
 * throwaway SQLite file - the same isolation {@code ImportToPracticeCriticalPathTest} (#164) first
 * proved out by hand with its own {@code @TempDir}/{@code @BeforeAll}/{@code @AfterAll} trio - instead
 * of the shared local {@code codefit.db} every repository/service integration test in this suite used
 * to touch (#175). The temp directory is created and torn down exactly like JUnit's own
 * {@code @TempDir} (same {@link Files#createTempDirectory} plus a recursive post-run delete), just
 * driven from an extension instead of an injected field so every migrated class only needs one
 * annotation instead of repeating the isolation boilerplate.
 *
 * <p>Always restores {@link DatabaseConfig#useDefaultDatabaseFile()} in {@code afterAll} - even if the
 * class's own {@code @BeforeAll} or a test throws - because {@code databaseUrl} is process-wide static
 * state (Surefire runs every test class in one JVM by default, per this project's {@code pom.xml}) and
 * every later test class would otherwise silently inherit whatever this class last pointed it at.
 * JUnit guarantees {@link AfterAllCallback#afterAll} runs whenever {@link BeforeAllCallback#beforeAll}
 * ran, regardless of what happened in between, which is what makes that guarantee possible here.
 *
 * <p>Usage: {@code @ExtendWith(IsolatedDatabaseExtension.class)} plus
 * {@code @ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)} on the test class. The resource
 * lock keys every migrated class to the same name, so if Maven/JUnit parallel test execution is ever
 * turned on for this module, the JUnit engine will serialize those classes against each other instead
 * of interleaving two classes' redirects of the one shared static {@code databaseUrl} field. This
 * project's default execution is already sequential in a single JVM (no {@code junit-platform.properties}
 * enables parallelism), so today the lock is a documented safety net rather than an active constraint -
 * see this project's {@code junit-platform.properties} for the explicit sequential-by-default policy.
 *
 * <p>Regression protection for #175 (proving the full suite never touches the shared default
 * database) is built directly into {@link #afterAll}: every migrated class's teardown re-asserts that
 * the repository-root {@code codefit.db} still does not exist, right after that class's own database
 * redirect is torn down. Because every repository/service integration test class in this suite is
 * annotated with this extension, that assertion effectively runs after every single one of them - a
 * stronger and more deterministic guarantee than a single check placed in one "last" test class, which
 * would depend on execution order to catch a regression in an earlier class.
 */
public final class IsolatedDatabaseExtension implements BeforeAllCallback, AfterAllCallback {

    /** Shared {@code @ResourceLock} key naming {@link DatabaseConfig}'s global static {@code databaseUrl}. */
    public static final String DATABASE_RESOURCE = "com.codefit.config.DatabaseConfig.databaseUrl";

    /** The repository-root database file every isolated test class must never create or touch. */
    static final Path DEFAULT_DATABASE_PATH = Path.of("codefit.db");

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(IsolatedDatabaseExtension.class);
    private static final String TEMP_DIR_KEY = "tempDir";

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        Path tempDir = Files.createTempDirectory("codefit-isolated-db-");
        context.getStore(NAMESPACE).put(TEMP_DIR_KEY, tempDir);
        DatabaseConfig.useDatabaseFile(tempDir.resolve("test.db"));
        DatabaseConfig.initialize();
    }

    @Override
    public void afterAll(ExtensionContext context) throws IOException {
        DatabaseConfig.useDefaultDatabaseFile();
        try {
            Path tempDir = context.getStore(NAMESPACE).remove(TEMP_DIR_KEY, Path.class);
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        } finally {
            assertDefaultDatabaseUntouched(context);
        }
    }

    private static void assertDefaultDatabaseUntouched(ExtensionContext context) {
        if (Files.exists(DEFAULT_DATABASE_PATH)) {
            throw new AssertionError("The repository-root " + DEFAULT_DATABASE_PATH.toAbsolutePath()
                    + " exists after " + context.getRequiredTestClass().getName() + " finished - every "
                    + "repository/service integration test must run against its own isolated database "
                    + "via " + IsolatedDatabaseExtension.class.getSimpleName() + " instead of the shared "
                    + "default database (see issue #175).");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
