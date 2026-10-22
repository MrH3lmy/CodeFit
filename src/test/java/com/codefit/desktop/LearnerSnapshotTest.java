package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class LearnerSnapshotTest {
    @TempDir Path temporary;

    private Path source() throws Exception {
        Path p = temporary.resolve(UUID.randomUUID() + ".db");
        LearnerFixture.create(p);
        return p;
    }

    private LearnerSnapshot capture(Path p) throws Exception {
        return LearnerSnapshot.capture(
                p, temporary.resolve("snapshots"), UUID.randomUUID().toString());
    }

    private void execute(Path p, String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + p);
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void sourceIsUnchangedAndBackupRestoresRecordedFactsWithoutReconciliation() throws Exception {
        Path p = source();
        byte[] before = Files.readAllBytes(p);
        String prior = DatabaseConfig.currentDatabaseUrl();
        try (var snapshot = capture(p)) {
            var value = snapshot.read();
            assertEquals("Ada Learner", value.displayName());
            assertEquals(750L, value.xp());
            assertEquals(8L, value.level());
            assertEquals(4, value.reviewCount());
            assertEquals(3, value.dueScheduledCards());
            String json =
                    new String(
                            LearnerProtocol.result(UUID.randomUUID().toString(), value),
                            StandardCharsets.UTF_8);
            assertFalse(json.contains("PRIVATE-"));
            assertFalse(json.contains(p.toString()));
            assertTrue(json.contains("\"mastery\":{\"status\":\"unavailable\"}"));
            assertArrayEquals(before, Files.readAllBytes(p));
            assertEquals(prior, DatabaseConfig.currentDatabaseUrl());
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(snapshot.database()));
            Path restored = temporary.resolve("restored.db");
            Files.copy(snapshot.database(), restored);
            try (var c = LearnerSnapshot.openReadOnly(restored, false);
                    var s = c.createStatement()) {
                assertThrows(
                        SQLException.class,
                        () -> s.executeUpdate("UPDATE user_progress SET xp=999"));
                try (var r = s.executeQuery("SELECT xp FROM user_progress")) {
                    assertTrue(r.next());
                    assertEquals(750, r.getInt(1));
                }
                try (var r = s.executeQuery("SELECT count(*) FROM evolution_reward_entitlements")) {
                    assertTrue(r.next());
                    assertEquals(0, r.getInt(1));
                }
            }
        }
    }

    @Test
    void missingOrMalformedFactsStayUnavailableWithoutInventedDefaults() throws Exception {
        Path p = source();
        execute(p, "DELETE FROM user_progress");
        execute(p, "UPDATE social_profile SET display_name=hex(zeroblob(5000))");
        try (var snapshot = capture(p)) {
            assertNull(snapshot.read().xp());
            assertNull(snapshot.read().level());
            assertNull(snapshot.read().displayName());
        }
        execute(p, "INSERT INTO user_progress(id,xp,level) VALUES(1,-1,0)");
        try (var snapshot = capture(p)) {
            assertNull(snapshot.read().xp());
        }
    }

    @Test
    void olderFutureIncompleteForeignAndExperimentalSourcesFailClosed() throws Exception {
        for (String change :
                List.of(
                        "DELETE FROM schema_migrations WHERE version=25",
                        "INSERT INTO schema_migrations(version,description) VALUES(26,'future')",
                        "DELETE FROM schema_migrations WHERE version=12",
                        "CREATE TABLE desktop_profile(id TEXT)",
                        "DROP TABLE social_profile")) {
            Path p = source();
            execute(p, change);
            byte[] before = Files.readAllBytes(p);
            assertThrows(Exception.class, () -> capture(p));
            assertArrayEquals(before, Files.readAllBytes(p));
        }
        Path foreign = temporary.resolve("foreign.db");
        Files.writeString(foreign, "private sentinel");
        assertThrows(Exception.class, () -> capture(foreign));
        assertEquals("private sentinel", Files.readString(foreign));
    }

    @Test
    void currentJavaFxOwnershipAndFileAliasesAreRejectedBeforeRead() throws Exception {
        Path p = source();
        byte[] before = Files.readAllBytes(p);
        try (var lock = ProfileLock.acquire(p)) {
            assertEquals(
                    "PROFILE_BUSY", assertThrows(IOException.class, () -> capture(p)).getMessage());
        }
        Path link = temporary.resolve("link.db");
        Files.createSymbolicLink(link, p);
        assertThrows(IOException.class, () -> capture(link));
        Path hard = temporary.resolve("hard.db");
        Files.createLink(hard, p);
        assertThrows(IOException.class, () -> capture(hard));
        Files.delete(hard);
        assertArrayEquals(before, Files.readAllBytes(p));
    }

    @Test
    void realProtocolChecksSessionIdentityAndRejectsWriteCommandsAcrossRestart() throws Exception {
        Path p = source(), store = temporary.resolve("snapshots");
        byte[] before = Files.readAllBytes(p);
        String session = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        Process process = launchReader(p, store, session);
        try {
            var reader = process.inputReader();
            String ready = reader.readLine();
            assertNotNull(ready);
            String identity = ready.replaceAll(".*\"profileId\":\"([a-f0-9]{64})\".*", "$1");
            assertTrue(identity.matches("[a-f0-9]{64}"), ready);
            String request =
                    "{\"v\":3,\"id\":\""
                            + id
                            + "\",\"method\":\"learner.overview.read\",\"profileId\":\""
                            + identity
                            + "\",\"sessionId\":\""
                            + session
                            + "\"}";
            process.getOutputStream()
                    .write(
                            (request.replace(session, UUID.randomUUID().toString()) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue(reader.readLine().contains("PROFILE_MISMATCH"));
            process.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            String result = reader.readLine();
            assertTrue(result.contains("\"xp\":750"));
            assertFalse(result.contains("PRIVATE-"));
            process.getOutputStream()
                    .write(
                            (request.replace("learner.overview.read", "decks.create") + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
        Process restarted = launchReader(p, store, UUID.randomUUID().toString());
        try {
            assertTrue(restarted.inputReader().readLine().contains("\"type\":\"ready\""));
            restarted.getOutputStream().close();
            assertTrue(restarted.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, restarted.exitValue());
        } finally {
            restarted.destroyForcibly();
        }
        assertArrayEquals(before, Files.readAllBytes(p));
    }

    @Test
    void publishedBackupIsFrozenAndSurvivesSourceChangesRestartAndFailedCapture() throws Exception {
        Path p = source(), backup;
        try (var snapshot = capture(p)) {
            backup = snapshot.database();
            execute(p, "UPDATE user_progress SET xp=850");
            assertEquals(750L, snapshot.read().xp());
        }
        byte[] prior = Files.readAllBytes(backup);
        execute(p, "DELETE FROM schema_migrations WHERE version=25");
        assertThrows(Exception.class, () -> capture(p));
        assertArrayEquals(prior, Files.readAllBytes(backup));
        Path recovery = temporary.resolve("recovery.db");
        Files.copy(backup, recovery);
        try (var snapshot = capture(recovery)) {
            assertEquals(750L, snapshot.read().xp());
        }
    }

    @Test
    void readOnlyWalSourceCapturesCommittedStateAndHotJournalIsNotRecovered() throws Exception {
        Path wal = temporary.resolve("wal.db");
        Process writer = launch(wal, "wal");
        try {
            assertEquals("ready", writer.inputReader().readLine());
            try (var snapshot = capture(wal)) {
                assertEquals(751L, snapshot.read().xp());
            }
        } finally {
            writer.getOutputStream().close();
            assertTrue(writer.waitFor(10, TimeUnit.SECONDS));
            writer.destroyForcibly();
        }
        Path hot = temporary.resolve("hot.db");
        Process uncommitted = launch(hot, "hot");
        assertEquals("staged", uncommitted.inputReader().readLine());
        uncommitted.destroyForcibly();
        assertTrue(uncommitted.waitFor(10, TimeUnit.SECONDS));
        Path journal = Path.of(hot + "-journal");
        assertTrue(Files.size(journal) > 0);
        byte[] db = Files.readAllBytes(hot), log = Files.readAllBytes(journal);
        assertThrows(Exception.class, () -> capture(hot));
        assertArrayEquals(db, Files.readAllBytes(hot));
        assertArrayEquals(log, Files.readAllBytes(journal));
    }

    @Test
    void retainedGenerationLimitAndRepeatedSessionCannotOverwriteGoodBackups() throws Exception {
        Path p = source(), store = temporary.resolve("snapshots");
        String id = UUID.randomUUID().toString();
        Path backup;
        try (var s = LearnerSnapshot.capture(p, store, id)) {
            backup = s.database();
        }
        byte[] before = Files.readAllBytes(backup);
        assertThrows(Exception.class, () -> LearnerSnapshot.capture(p, store, id));
        assertArrayEquals(before, Files.readAllBytes(backup));
        for (int i = 1; i < LearnerSnapshot.MAX_BACKUPS; i++) try (var ignored = capture(p)) {}
        assertEquals(
                "SNAPSHOT_LIMIT", assertThrows(IOException.class, () -> capture(p)).getMessage());
        assertArrayEquals(before, Files.readAllBytes(backup));
    }

    @Test
    void interruptedCandidatesAndOversizedSourcesNeverReplacePublishedGenerations()
            throws Exception {
        Path p = source(), store = temporary.resolve("snapshots"), good;
        try (var snapshot = capture(p)) {
            good = snapshot.database();
        }
        Path interrupted = store.resolve(UUID.randomUUID() + ".partial");
        Files.writeString(interrupted, "interrupted capture");
        byte[] before = Files.readAllBytes(good);
        try (var ignored = capture(p)) {}
        assertEquals("interrupted capture", Files.readString(interrupted));
        assertArrayEquals(before, Files.readAllBytes(good));
        Path huge = temporary.resolve("huge.db");
        try (var file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(LearnerSnapshot.MAX_BYTES + 1);
        }
        assertThrows(IOException.class, () -> capture(huge));
        assertArrayEquals(before, Files.readAllBytes(good));
    }

    private Process launch(Path p, String mode) throws IOException {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        LearnerFixture.class.getName(),
                        p.toString(),
                        mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private Process launchReader(Path p, Path store, String session) throws IOException {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        LearnerProfileMain.class.getName(),
                        "--parent",
                        Long.toString(ProcessHandle.current().pid()),
                        "--source",
                        p.toString(),
                        "--snapshots",
                        store.toString(),
                        "--session",
                        session)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
