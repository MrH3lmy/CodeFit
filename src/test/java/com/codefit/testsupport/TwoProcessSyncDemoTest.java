package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.sync.SyncOutcome;
import com.codefit.repository.PeerProgressSummaryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #184's required end-to-end acceptance test: two genuinely separate OS processes (own JVM, own
 * throwaway SQLite file, own identity/transport keys - exactly {@code TwoProcessPeerDemoTest}'s
 * established pattern for #182, extended to drive the real {@code PeerSyncSessionService} sync
 * engine) walk through the full scenario the issue describes: first sync, disconnect, restart, a
 * genuinely newer revision, reconnect with only the new revision arriving, duplicate delivery being
 * harmless, revocation, a cooperating peer purging its cache on the matching tombstone, and a replay
 * of the pre-revocation copy failing to resurrect it.
 *
 * <p>No central service of any kind participates: every step is either a single short-lived process
 * acting on its own database, or exactly two such processes connected directly to each other over a
 * loopback socket. Between steps, this test inspects each side's SQLite file directly (after the
 * owning process has fully exited) via {@code DatabaseConfig.useDatabaseFile} in its own JVM -
 * never the developer's real {@code codefit.db}.
 */
class TwoProcessSyncDemoTest {

    private Path workDir;
    private Path dbA;
    private Path dbB;
    private String classpath;
    private String javaBin;
    private final String savedDatabaseUrl = DatabaseConfig.currentDatabaseUrl();

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createTempDirectory("codefit-two-process-sync-demo");
        dbA = workDir.resolve("a.db");
        dbB = workDir.resolve("b.db");
        classpath = System.getProperty("java.class.path");
        javaBin = System.getProperty("java.home") + "/bin/java";
    }

    @AfterEach
    void tearDown() {
        DatabaseConfig.useDatabaseUrl(savedDatabaseUrl);
    }

    @Test
    @Timeout(180)
    void fullSyncLifecycleAcrossRestartRevisionDuplicationAndRevocation() throws Exception {
        Path inviteA = workDir.resolve("a-invite.txt");
        Path inviteB = workDir.resolve("b-invite.txt");
        Path portA = workDir.resolve("a-port.txt");
        Path portB = workDir.resolve("b-port.txt");
        Path captureB = workDir.resolve("b-captured-frames.txt");

        // --- Pairing (each a short-lived process against its own, now-persisted database) ---
        runSingle("A", dbA, "INIT", inviteA.toString(), portA.toString());
        runSingle("B", dbB, "INIT", inviteB.toString(), portB.toString());
        runSingle("A", dbA, "PAIR", inviteB.toString());
        runSingle("B", dbB, "PAIR", inviteA.toString());

        // --- A approves sharing one day's progress summary with B ---
        String windowDate = "2026-01-15";
        runSingle("A", dbA, "GRANT", windowDate);

        // --- First sync: A (listener) <-> B (dialer). B should receive A's revision 1. ---
        runSyncPair(dbA, dbB, portA, portB, captureB);
        IdentityId authorIdentityId = readAuthorIdentityId(dbA);
        assertRevisionAndRowCount(dbB, authorIdentityId, 1L, 1);

        // --- Disconnect + restart are implicit: every process launch above/below is already a fresh
        // JVM reloading persisted state from the SQLite file, proving restart survival structurally. ---

        // --- A records genuinely new study evidence for the SAME day, then republishes. ---
        runSingle("A", dbA, "ADD_EVIDENCE", windowDate);
        runSingle("A", dbA, "GRANT", windowDate); // idempotent re-approve; re-asserts the grant is still current

        // --- Reconnect: B must receive only the newer revision, as ONE updated row (never a duplicate object). ---
        runSyncPair(dbA, dbB, portA, portB, captureB);
        assertRevisionAndRowCount(dbB, authorIdentityId, 2L, 1);

        // --- Duplicate/reordered delivery: reconnecting again with nothing new must be harmless. ---
        runSyncPair(dbA, dbB, portA, portB, captureB);
        assertRevisionAndRowCount(dbB, authorIdentityId, 2L, 1);

        // --- Revoke A -> B sharing. ---
        runSingle("A", dbA, "REVOKE");

        // --- Reconnect: B must process the revocation/tombstone and purge its cached copy. ---
        runSyncPair(dbA, dbB, portA, portB, captureB);
        assertNoRowsForAuthor(dbB, authorIdentityId);

        // --- Anti-resurrection: replay the ORIGINAL, pre-revocation revision 1 frame directly at B
        // (as a cooperating peer that received it before the revocation would still hold a copy to
        // replay). It must not resurrect. ---
        runSingle("B", dbB, "REPLAY", captureB.toString(), "1");
        Path replayOutcomeFile = captureB.resolveSibling(captureB.getFileName().toString() + ".replay-outcome");
        String replayOutcome = Files.readString(replayOutcomeFile).strip();
        assertFalse(SyncOutcome.valueOf(replayOutcome).accepted(), "a replayed pre-revocation copy must never be (re-)accepted");
        assertNoRowsForAuthor(dbB, authorIdentityId);
    }

    // --- helpers ---

    private void runSingle(String role, Path db, String action, String... extraArgs) throws Exception {
        Path result = workDir.resolve(role.toLowerCase() + "-" + action.toLowerCase() + "-result.txt");
        List<String> command = new java.util.ArrayList<>(List.of(javaBin, "-cp", classpath,
                "com.codefit.testsupport.TwoProcessSyncDemo", role, db.toString(), action, result.toString()));
        command.addAll(List.of(extraArgs));
        Process process = new ProcessBuilder(command).inheritIO().start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String resultText = Files.exists(result) ? Files.readString(result) : "";
        assertTrue(finished, role + " " + action + " did not finish in time. result=" + resultText);
        assertEquals(0, process.exitValue(), role + " " + action + " failed: " + resultText);
        assertTrue(resultText.startsWith("SUCCESS"), role + " " + action + " result: " + resultText);
    }

    /** Launches A as LISTENER and B as DIALER concurrently for one sync connection, waits for both. */
    private void runSyncPair(Path dbA, Path dbB, Path portA, Path portB, Path captureB) throws Exception {
        Path resultA = workDir.resolve("a-sync-result.txt");
        Path resultB = workDir.resolve("b-sync-result.txt");
        Files.deleteIfExists(resultA);
        Files.deleteIfExists(resultB);
        // Each SYNC launch re-enables networking on a new ephemeral port; delete any port published
        // by an earlier INIT/SYNC launch so the dialer only ever reads the CURRENT listener's port.
        Files.deleteIfExists(portA);
        Files.deleteIfExists(portB);

        Process listener = new ProcessBuilder(List.of(javaBin, "-cp", classpath,
                "com.codefit.testsupport.TwoProcessSyncDemo", "A", dbA.toString(), "SYNC", resultA.toString(),
                "LISTENER", portA.toString(), portB.toString(), workDir.resolve("a-captured-frames.txt").toString()))
                .inheritIO().start();
        Process dialer = new ProcessBuilder(List.of(javaBin, "-cp", classpath,
                "com.codefit.testsupport.TwoProcessSyncDemo", "B", dbB.toString(), "SYNC", resultB.toString(),
                "DIALER", portB.toString(), portA.toString(), captureB.toString()))
                .inheritIO().start();

        boolean listenerFinished = listener.waitFor(40, TimeUnit.SECONDS);
        boolean dialerFinished = dialer.waitFor(40, TimeUnit.SECONDS);
        String listenerResult = Files.exists(resultA) ? Files.readString(resultA) : "";
        String dialerResult = Files.exists(resultB) ? Files.readString(resultB) : "";
        assertTrue(listenerFinished, "listener (A) sync did not finish in time: " + listenerResult);
        assertTrue(dialerFinished, "dialer (B) sync did not finish in time: " + dialerResult);
        assertTrue(listenerResult.startsWith("SUCCESS"), "A sync result: " + listenerResult);
        assertTrue(dialerResult.startsWith("SUCCESS"), "B sync result: " + dialerResult);
    }

    private IdentityId readAuthorIdentityId(Path db) {
        DatabaseConfig.useDatabaseFile(db);
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT identity_public_key FROM peer_identity WHERE id = 1");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            byte[] publicKey = resultSet.getBytes("identity_public_key");
            return new com.codefit.peer.protocol.IdentityKey(publicKey).id();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertRevisionAndRowCount(Path db, IdentityId author, long expectedRevision, int expectedRowCount) {
        DatabaseConfig.useDatabaseFile(db);
        List<com.codefit.peer.sync.PeerProgressSummary> cached = new PeerProgressSummaryRepository().findByAuthor(author);
        assertEquals(expectedRowCount, cached.size(), "cached peer progress summary row count for " + author);
        if (expectedRowCount > 0) {
            assertEquals(expectedRevision, cached.get(0).revision(), "cached peer progress summary revision");
        }
    }

    private void assertNoRowsForAuthor(Path db, IdentityId author) {
        DatabaseConfig.useDatabaseFile(db);
        assertTrue(new PeerProgressSummaryRepository().findByAuthor(author).isEmpty(),
                "a revoked, tombstoned object must have been purged from the peer inbox cache");
    }
}
