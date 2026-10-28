package com.codefit.desktop;

import com.codefit.config.DatabaseConfig;
import com.codefit.config.ProfileLock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.Set;
import java.util.UUID;

/**
 * Dedicated experimental data only. Never imports, migrates, or opens an unmarked learner profile.
 */
final class ExperimentalProfile implements AutoCloseable {
    private static final String MARKER = "CodeFit experimental library / version 1\n";
    private final Path directory;
    private final Path database;
    private final ProfileLock owner;
    private final boolean learning;
    private String id;
    private boolean closed;

    static ExperimentalProfile open(Path path) throws IOException, SQLException {
        return open(path, MARKER);
    }

    static ExperimentalProfile openLearning(Path path) throws IOException, SQLException {
        if (!System.getProperty("os.name").equals("Linux"))
            throw new IOException("PROFILE_INVALID");
        return open(path, "CodeFit disposable experimental practice / version 1\n");
    }

    private static ExperimentalProfile open(Path path, String expectedMarker)
            throws IOException, SQLException {
        Path absolute = path.toAbsolutePath().normalize();
        boolean learning = !expectedMarker.equals(MARKER);
        if (learning) {
            Files.createDirectories(
                    absolute,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            if (!Files.getPosixFilePermissions(absolute)
                    .equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
                throw new IOException("PROFILE_INVALID");
        } else Files.createDirectories(absolute);
        if (!absolute.toRealPath().equals(absolute)) throw new IOException("PROFILE_INVALID");
        Path marker = absolute.resolve("experimental-profile.txt");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(absolute)) {
                if (entries.findAny().isPresent()) throw new IOException("PROFILE_INVALID");
            }
        } else if (Files.isSymbolicLink(marker)
                || Files.size(marker) != expectedMarker.length()
                || !Files.readString(marker).equals(expectedMarker))
            throw new IOException("PROFILE_INVALID");
        for (String name :
                Set.of(
                        "codefit.db",
                        "codefit.db-journal",
                        "codefit.db-wal",
                        "codefit.db-shm",
                        "bootstrap.db",
                        "bootstrap.db-journal",
                        "bootstrap.db-wal",
                        "bootstrap.db-shm")) {
            Path candidate = absolute.resolve(name);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("PROFILE_INVALID");
        }
        ProfileLock owner = ProfileLock.acquire(absolute.resolve("codefit.db"));
        ExperimentalProfile profile = new ExperimentalProfile(absolute, owner, learning);
        try {
            if (!Files.exists(marker)) {
                Files.writeString(marker, expectedMarker, StandardOpenOption.CREATE_NEW);
                if (learning)
                    Files.setPosixFilePermissions(
                            marker,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                force(marker);
            }
            if (!Files.exists(profile.database)) profile.bootstrap();
            if (learning)
                Files.setPosixFilePermissions(
                        profile.database,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            profile.verifyAndRecover();
            if (learning) {
                PracticeDraftSchema.install(profile);
                PracticeSubmissionSchema.install(profile);
                PracticeSkillsSchema.install(profile);
            }
            return profile;
        } catch (IOException | SQLException | RuntimeException failure) {
            profile.close();
            throw failure;
        }
    }

    private ExperimentalProfile(Path directory, ProfileLock owner, boolean learning) {
        this.learning = learning;
        this.directory = directory;
        this.database = directory.resolve("codefit.db");
        this.owner = owner;
    }

    private void bootstrap() throws IOException, SQLException {
        // No published DB means no command has ever been accepted. Only our unpublished staging
        // files
        // can be discarded after an interrupted bootstrap; a published database is never
        // overwritten.
        Set<String> allowed =
                Set.of(
                        "experimental-profile.txt",
                        "codefit.db.owner.lock",
                        "bootstrap.db",
                        "bootstrap.db-journal",
                        "bootstrap.db-wal",
                        "bootstrap.db-shm");
        try (var entries = Files.list(directory)) {
            if (entries.anyMatch(p -> !allowed.contains(p.getFileName().toString())))
                throw new IOException("PROFILE_INVALID");
        }
        for (String name :
                new String[] {
                    "bootstrap.db-journal", "bootstrap.db-wal", "bootstrap.db-shm", "bootstrap.db"
                }) Files.deleteIfExists(directory.resolve(name));
        Path staging = directory.resolve("bootstrap.db");
        String previous = DatabaseConfig.currentDatabaseUrl();
        try {
            DatabaseConfig.useDatabaseFile(staging);
            DatabaseConfig.initialize();
            try (Connection c = DatabaseConfig.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(
                        "CREATE TABLE desktop_profile (id TEXT PRIMARY KEY, version INTEGER NOT"
                                + " NULL CHECK(version=1))");
                try (var insert = c.prepareStatement("INSERT INTO desktop_profile VALUES (?,1)")) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.executeUpdate();
                }
                s.execute(
                        """
CREATE TABLE desktop_commands (
    command_id TEXT PRIMARY KEY,
    input_name TEXT NOT NULL,
    input_description TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('created','rejected')),
    deck_id INTEGER,
    deck_name TEXT,
    deck_description TEXT,
    CHECK ((status='created' AND deck_id IS NOT NULL AND deck_name IS NOT NULL
        AND deck_description IS NOT NULL) OR (status='rejected' AND deck_id IS NULL))
)
""");
                if (learning) {
                    s.execute(
                            "CREATE TABLE desktop_practice_commands (command_id TEXT PRIMARY KEY,"
                                    + " quest_id TEXT NOT NULL, quest_version INTEGER NOT NULL,"
                                    + " challenge_id TEXT NOT NULL, challenge_version INTEGER NOT"
                                    + " NULL)");
                    s.execute(
                            "CREATE TRIGGER desktop_practice_commands_no_update BEFORE UPDATE ON"
                                + " desktop_practice_commands BEGIN SELECT RAISE(ABORT,'Immutable"
                                + " practice command'); END");
                    s.execute(
                            "CREATE TRIGGER desktop_practice_commands_no_delete BEFORE DELETE ON"
                                + " desktop_practice_commands BEGIN SELECT RAISE(ABORT,'Immutable"
                                + " practice command'); END");
                }
            }
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
        force(staging);
        Files.move(staging, database, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory();
    }

    private void verifyAndRecover() throws SQLException, IOException {
        // Explicit startup recovery may roll back a hot SQLite journal while holding the owner
        // lock.
        // It never invokes the application's schema initializer against an existing profile.
        try (Connection c = connection(false);
                Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !"ok".equals(r.getString(1)))
                    throw new IOException("PROFILE_INVALID");
            }
            try (ResultSet r = s.executeQuery("SELECT id, version FROM desktop_profile")) {
                if (!r.next() || r.getInt(2) != 1) throw new IOException("PROFILE_INVALID");
                id = r.getString(1);
                if (!ApplicationProtocol.uuid(id) || r.next())
                    throw new IOException("PROFILE_INVALID");
            }
            if (learning) {
                try (ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
                    if (r.next()) throw new IOException("PROFILE_INVALID");
                }
                try (ResultSet ignored =
                        s.executeQuery(
                                "SELECT"
                                    + " command_id,quest_id,quest_version,challenge_id,challenge_version"
                                    + " FROM desktop_practice_commands LIMIT 0")) {}
            }
            // Gate2 is deliberately not a migration entrypoint. Require exactly the audited base
            // schema; a future release must review migration + backup before raising this version.
            try (ResultSet r =
                    s.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
                for (int expected = 1; expected <= 25; expected++)
                    if (!r.next() || r.getInt(1) != expected)
                        throw new IOException("PROFILE_INVALID");
                if (r.next()) throw new IOException("PROFILE_INVALID");
            }
            try (ResultSet ignored =
                    s.executeQuery(
                            "SELECT command_id, input_name, input_description, status, deck_id,"
                                + " deck_name, deck_description FROM desktop_commands LIMIT 0")) {}
        }
    }

    boolean isLearning() {
        return learning;
    }

    String id() {
        return id;
    }

    Path database() {
        return database;
    }

    Connection connection(boolean readOnly) throws SQLException {
        if (closed) throw new SQLException("PROFILE_CLOSED");
        Connection c =
                DriverManager.getConnection(
                        "jdbc:sqlite:"
                                + database.toUri().toASCIIString()
                                + (readOnly ? "?mode=ro" : "?mode=rw"));
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=1000");
            s.execute("PRAGMA foreign_keys=ON");
            if (readOnly) s.execute("PRAGMA query_only=ON");
            else s.execute("PRAGMA synchronous=FULL");
            return c;
        } catch (SQLException failure) {
            c.close();
            throw failure;
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory() throws IOException {
        // Java cannot open directory channels on Windows. SQLite still supplies transaction
        // durability;
        // atomic publication is used on every platform, directory durability is additionally synced
        // on Unix.
        if (!System.getProperty("os.name").startsWith("Windows"))
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        owner.close();
    }
}
