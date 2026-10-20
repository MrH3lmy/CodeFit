package com.codefit.desktop;

import com.codefit.config.DatabaseConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Owns only a newly created throwaway directory. Never accepts an existing learner database. */
final class IsolatedDesktopDatabase implements AutoCloseable {
    private final Path directory;
    private final String previousUrl;
    private boolean initialized;
    private boolean closed;

    IsolatedDesktopDatabase() throws IOException {
        directory = Files.createTempDirectory("codefit-gate0-");
        previousUrl = DatabaseConfig.currentDatabaseUrl();
    }

    synchronized void initialize() {
        if (closed || initialized) throw new IllegalStateException("INVALID_SESSION_STATE");
        try {
            String uri = directory.resolve("sample.db").toUri().toASCIIString();
            DatabaseConfig.useDatabaseUrl("jdbc:sqlite:" + uri);
            DatabaseConfig.initialize();
            // All initializer connections are closed. No backfill, peer, or UI lifecycle is
            // started.
            DatabaseConfig.useDatabaseUrl("jdbc:sqlite:" + uri + "?mode=ro&immutable=1");
            initialized = true;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Shutdown cannot restore the process-wide URL while the service is acquiring connections. */
    synchronized <T> T read(Supplier<T> operation) {
        if (closed || !initialized) throw new IllegalStateException("INVALID_SESSION_STATE");
        return operation.get();
    }

    Path directory() {
        return directory;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        DatabaseConfig.useDatabaseUrl(previousUrl);
        // Delete only this instance's own known files, never traverse a user-supplied tree.
        for (String name :
                new String[] {"sample.db-journal", "sample.db-wal", "sample.db-shm", "sample.db"}) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException ignored) {
                System.err.println("GATE0_TEMP_CLEANUP_FAILED");
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            System.err.println("GATE0_TEMP_CLEANUP_FAILED");
        }
    }
}
