package com.codefit.desktop;

import com.codefit.config.ProfileLock;

import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Explicitly selected learner source; never calls DatabaseConfig, initialization or any writer
 * service.
 */
final class LearnerSnapshot implements AutoCloseable {
    static final int SCHEMA_VERSION = 25;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_BACKUPS = 8;
    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private final Path database;
    private final String profileId;
    private final String sessionId;
    private final Instant capturedAt;
    private final ProfileLock storeLock;

    private LearnerSnapshot(
            Path database,
            String profileId,
            String sessionId,
            Instant capturedAt,
            ProfileLock storeLock) {
        this.database = database;
        this.profileId = profileId;
        this.sessionId = sessionId;
        this.capturedAt = capturedAt;
        this.storeLock = storeLock;
    }

    static LearnerSnapshot capture(Path selected, Path storage, String sessionId) throws Exception {
        if (!ApplicationProtocol.uuid(sessionId)) throw new IOException("INVALID_INPUT");
        Path source = selected.toAbsolutePath().normalize();
        BasicFileAttributes original = checkedFile(source, MAX_BYTES);
        checkSidecars(source);
        Path root = privateDirectory(storage);
        if (source.startsWith(root)) throw new IOException("PROFILE_INVALID");
        Path lockFile = root.resolve("snapshot-store");
        validateLockSidecar(lockFile);
        ProfileLock store = ProfileLock.acquire(lockFile);
        Path candidate = root.resolve(sessionId + ".partial");
        Path complete = root.resolve(sessionId + ".sqlite");
        boolean success = false;
        boolean allocated = false;
        try {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(complete, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("PROFILE_INVALID");
            // Count incomplete generations too: an interrupted capture cannot bypass disk bounds.
            try (var entries = Files.list(root)) {
                long count =
                        entries.filter(
                                        p ->
                                                p.getFileName()
                                                        .toString()
                                                        .matches(
                                                                "[0-9a-f-]{36}\\.(partial|sqlite)"))
                                .count();
                if (count >= MAX_BACKUPS) throw new IOException("SNAPSHOT_LIMIT");
            }
            validateLockSidecar(source);
            Instant capturedAt;
            try (ProfileLock ownership = ProfileLock.acquire(source);
                    Connection read = openReadOnly(source, false)) {
                if (!Objects.equals(original.fileKey(), checkedFile(source, MAX_BYTES).fileKey()))
                    throw new IOException("SOURCE_CHANGED");
                read.setAutoCommit(false);
                // These schema SELECTs establish the SQLite read snapshot before online backup.
                validateSchema(read);
                capturedAt = Instant.now();
                try (var s = read.createStatement();
                        var r = s.executeQuery("PRAGMA page_count")) {
                    if (!r.next()) throw new IOException("PROFILE_INVALID");
                    long pages = r.getLong(1);
                    try (var ps = read.createStatement();
                            var pr = ps.executeQuery("PRAGMA page_size")) {
                        if (!pr.next() || pages < 1 || pages > MAX_BYTES / pr.getLong(1))
                            throw new IOException("PROFILE_INVALID");
                    }
                }
                Files.createFile(candidate, PosixFilePermissions.asFileAttribute(FILE_MODE));
                allocated = true;
                // sqlite3_backup is transactionally consistent. Observer exceptions do NOT
                // interrupt
                // the native loop in this driver; LearnerProfileMain and Electron impose hard
                // process deadlines.
                int result =
                        ((SQLiteConnection) read)
                                .getDatabase()
                                .backup("main", candidate.toString(), null, 256, 5, 20);
                if (result != 0) throw new IOException("SNAPSHOT_FAILED");
                read.rollback();
                checkSidecars(source);
                if (!Objects.equals(original.fileKey(), checkedFile(source, MAX_BYTES).fileKey()))
                    throw new IOException("SOURCE_CHANGED");
            }
            checkedFile(candidate, MAX_BYTES);
            try (Connection backup = openReadOnly(candidate, false)) {
                validateSchema(backup);
                try (var s = backup.createStatement();
                        var r = s.executeQuery("PRAGMA integrity_check(1)")) {
                    if (!r.next() || !"ok".equals(r.getString(1)) || r.next())
                        throw new IOException("PROFILE_INVALID");
                }
                // Validate exactly the projection we will expose before publishing this generation.
                LearnerReadProjection.read(backup, "0".repeat(64), sessionId, capturedAt);
            }
            try (FileChannel file =
                    FileChannel.open(
                            candidate, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                file.force(true);
            }
            // Never fall back to non-atomic replacement, and never replace a prior good generation.
            Files.move(candidate, complete, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
            String identity =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (source + "\n" + original.fileKey())
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)));
            success = true;
            return new LearnerSnapshot(complete, identity, sessionId, capturedAt, store);
        } finally {
            if (!success) {
                // Delete only the uncommitted filename allocated by this attempt. Existing backups
                // survive.
                try {
                    if (allocated) Files.deleteIfExists(candidate);
                } finally {
                    store.close();
                }
            }
        }
    }

    private static Path privateDirectory(Path path) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        checkedAncestors(root.getParent());
        requireLocalFileSystem(root.getParent());
        if (!Files.getFileStore(root.getParent()).supportsFileAttributeView("posix"))
            throw new IOException("UNSUPPORTED_PLATFORM");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        checkedAncestors(root);
        var attrs =
                Files.readAttributes(root, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory()
                || !attrs.permissions().equals(DIRECTORY_MODE)
                || !attrs.owner().getName().equals(System.getProperty("user.name")))
            throw new IOException("PROFILE_INVALID");
        return root;
    }

    static BasicFileAttributes checkedFile(Path file, long max) throws IOException {
        checkedAncestors(file);
        requireLocalFileSystem(file);
        var attrs =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()
                || attrs.fileKey() == null
                || attrs.size() < 1
                || attrs.size() > max) throw new IOException("PROFILE_INVALID");
        if (!file.getFileSystem().supportedFileAttributeViews().contains("unix"))
            throw new IOException("UNSUPPORTED_PLATFORM");
        if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue()
                != 1) throw new IOException("PROFILE_INVALID");
        return attrs;
    }

    private static void requireLocalFileSystem(Path path) throws IOException {
        // Native lifecycle/locking validation currently covers Linux local filesystems only.
        // A file key on NFS/FUSE is not evidence of reliable SQLite ownership semantics.
        if (!System.getProperty("os.name").equals("Linux")
                || !Set.of("ext4", "xfs", "btrfs", "overlay", "tmpfs")
                        .contains(Files.getFileStore(path).type()))
            throw new IOException("UNSUPPORTED_PLATFORM");
    }

    private static void checkedAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("PROFILE_INVALID");
        }
    }

    private static void validateLockSidecar(Path database) throws IOException {
        Path lock = database.resolveSibling(database.getFileName() + ".owner.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            checkedAncestors(lock);
            var attr =
                    Files.readAttributes(
                            lock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attr.isRegularFile()
                    || attr.fileKey() == null
                    || ((Number) Files.getAttribute(lock, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                                    .longValue()
                            != 1) throw new IOException("PROFILE_INVALID");
        }
    }

    private static void checkSidecars(Path source) throws IOException {
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = source.resolveSibling(source.getFileName() + suffix);
            if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) continue;
            checkedAncestors(sidecar);
            var a =
                    Files.readAttributes(
                            sidecar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()
                    || a.fileKey() == null
                    || a.size() > MAX_BYTES
                    || ((Number)
                                            Files.getAttribute(
                                                    sidecar,
                                                    "unix:nlink",
                                                    LinkOption.NOFOLLOW_LINKS))
                                    .longValue()
                            != 1) throw new IOException("PROFILE_INVALID");
            if (suffix.equals("-journal") && a.size() > 0) throw new IOException("PROFILE_BUSY");
        }
    }

    static Connection openReadOnly(Path file, boolean immutable) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1000);
        Connection connection =
                DriverManager.getConnection(
                        "jdbc:sqlite:"
                                + file.toUri().toASCIIString()
                                + "?mode=ro"
                                + (immutable ? "&immutable=1" : ""),
                        config.toProperties());
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            ProgressHandler.setHandler(
                    connection,
                    1000,
                    new ProgressHandler() {
                        @Override
                        protected int progress() {
                            return System.nanoTime() > deadline ? 1 : 0;
                        }
                    });
            try (var s = connection.createStatement()) {
                s.execute("PRAGMA query_only=ON");
                s.execute("PRAGMA trusted_schema=OFF");
                s.execute("PRAGMA temp_store=MEMORY");
            }
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    static void validateSchema(Connection connection) throws SQLException, IOException {
        Map<String, Set<String>> tables =
                Map.of(
                        "schema_migrations", Set.of("version", "description", "applied_at"),
                        "user_progress", Set.of("id", "xp", "level", "total_reviews"),
                        "social_profile", Set.of("id", "display_name"),
                        "review_history", Set.of("id", "flashcard_id", "reviewed_at"),
                        "flashcards", Set.of("id", "deck_id", "due_date", "card_state"),
                        "decks", Set.of("id", "name", "description"),
                        "evolution_estimate_revisions", Set.of("revision", "inputs"),
                        "evolution_reward_effort_processed",
                                Set.of("submission_id", "disposition"));
        Set<String> found = new HashSet<>();
        try (var s = connection.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT 1 FROM sqlite_schema WHERE name='desktop_profile' LIMIT"
                                        + " 1")) {
            if (r.next()) throw new IOException("PROFILE_INVALID");
        }
        String names =
                tables.keySet().stream()
                        .map(name -> "'" + name + "'")
                        .collect(java.util.stream.Collectors.joining(","));
        try (var s = connection.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT name,type,CASE WHEN length(sql)<=65536 THEN sql END FROM"
                                        + " sqlite_schema WHERE name IN ("
                                        + names
                                        + ") LIMIT 9")) {
            while (r.next()) {
                String name = r.getString(1);
                if (name.equals("desktop_profile")) throw new IOException("PROFILE_INVALID");
                if (tables.containsKey(name)) {
                    String sql = r.getString(3);
                    if (!"table".equals(r.getString(2))
                            || sql == null
                            || !sql.stripLeading()
                                    .toUpperCase(Locale.ROOT)
                                    .startsWith("CREATE TABLE"))
                        throw new IOException("INCOMPATIBLE_SCHEMA");
                    found.add(name);
                }
            }
        }
        if (!found.equals(tables.keySet())) throw new IOException("INCOMPATIBLE_SCHEMA");
        for (var table : tables.entrySet()) {
            Set<String> columns = new HashSet<>();
            try (var s = connection.createStatement();
                    var r = s.executeQuery("PRAGMA table_info('" + table.getKey() + "')")) {
                while (r.next()) {
                    if (columns.size() >= 256) throw new IOException("INCOMPATIBLE_SCHEMA");
                    columns.add(r.getString("name"));
                }
            }
            if (!columns.containsAll(table.getValue()))
                throw new IOException("INCOMPATIBLE_SCHEMA");
        }
        try (var s = connection.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT version FROM schema_migrations ORDER BY version LIMIT"
                                        + " 26")) {
            for (int version = 1; version <= SCHEMA_VERSION; version++)
                if (!r.next()
                        || r.getLong(1) != version
                        || !"integer"
                                .equals(
                                        r.getMetaData()
                                                .getColumnTypeName(1)
                                                .toLowerCase(Locale.ROOT)))
                    throw new IOException("INCOMPATIBLE_SCHEMA");
            if (r.next()) throw new IOException("INCOMPATIBLE_SCHEMA");
        }
    }

    LearnerReadProjection.Overview read() throws Exception {
        checkedFile(database, MAX_BYTES);
        try (Connection connection = openReadOnly(database, false)) {
            connection.setAutoCommit(false);
            var result = LearnerReadProjection.read(connection, profileId, sessionId, capturedAt);
            connection.rollback();
            return result;
        }
    }

    LearnerSkillProjection.Projection readSkills() throws Exception {
        checkedFile(database, MAX_BYTES);
        try (Connection connection = openReadOnly(database, false)) {
            connection.setAutoCommit(false);
            var result = LearnerSkillProjection.read(connection, profileId, sessionId, capturedAt);
            connection.rollback();
            return result;
        }
    }

    Path database() {
        return database;
    }

    String profileId() {
        return profileId;
    }

    String sessionId() {
        return sessionId;
    }

    @Override
    public void close() throws IOException {
        storeLock.close();
    }
}
