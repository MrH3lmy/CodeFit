package com.codefit.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real-boundary proof PR A exists for: two genuinely separate OS processes (own JVM, own
 * throwaway SQLite file, own identity/transport keys), pairing and granting each other a real
 * sharing scope exactly like a real paired user would, then having one dial the other over real
 * mutual-TLS transport - and, from that point on, <strong>neither process's own code calls {@code
 * PeerSyncSessionService.startReceiving} or {@code sendOutboxTo}</strong>. Both directions' automatic
 * receive-loop-start and automatic outbox-send are instead driven entirely by {@code
 * NetworkingService}'s own application-lifetime orchestration (this PR's subject). Success is proven
 * by each process observing, in its own real database, that it received the other's real signed
 * consent envelope - which can only have happened if that other process's automatic send pass sent it
 * and this process's own automatic receive loop ingested it.
 */
class TwoProcessAutomaticSyncDemoTest {

    @Test
    @Timeout(90)
    void twoSeparateProcessesAutomaticallySyncOverARealAuthenticatedConnectionWithNoDirectSyncCalls() throws Exception {
        Path workDir = Files.createTempDirectory("codefit-two-process-automatic-sync-demo");
        Path dbA = workDir.resolve("a.db");
        Path dbB = workDir.resolve("b.db");
        Path inviteA = workDir.resolve("a-invite.txt");
        Path inviteB = workDir.resolve("b-invite.txt");
        Path resultA = workDir.resolve("a-result.txt");
        Path resultB = workDir.resolve("b-result.txt");

        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";

        // The listener writes its own invitation first, with no peer invitation to wait for yet.
        Process listenerProcess = startProcess(javaBin, classpath, "LISTENER", dbA, inviteA, inviteB, resultA, resultB);
        Process dialerProcess = startProcess(javaBin, classpath, "DIALER", dbB, inviteB, inviteA, resultB, resultA);

        boolean listenerFinished = listenerProcess.waitFor(80, TimeUnit.SECONDS);
        boolean dialerFinished = dialerProcess.waitFor(80, TimeUnit.SECONDS);

        String listenerResult = readResultOrEmpty(resultA);
        String dialerResult = readResultOrEmpty(resultB);

        assertTrue(listenerFinished, "listener process did not finish in time. result=" + listenerResult);
        assertTrue(dialerFinished, "dialer process did not finish in time. result=" + dialerResult);
        assertTrue(listenerResult.startsWith("SUCCESS"),
                "listener (process A) never automatically received the dialer's consent envelope: " + listenerResult);
        assertTrue(dialerResult.startsWith("SUCCESS"),
                "dialer (process B) never automatically received the listener's consent envelope: " + dialerResult);
        assertEquals(0, listenerProcess.exitValue(), "listener process exit code; result=" + listenerResult);
        assertEquals(0, dialerProcess.exitValue(), "dialer process exit code; result=" + dialerResult);

        // Independent databases: two distinct, non-empty SQLite files, each holding real evidence of
        // what that process alone pairs/grants/received - never touched by the other process directly.
        assertTrue(Files.size(dbA) > 0);
        assertTrue(Files.size(dbB) > 0);
    }

    private static Process startProcess(String javaBin, String classpath, String role, Path db, Path ownInvite,
                                         Path peerInvite, Path ownResult, Path peerResult) throws IOException {
        List<String> command = List.of(javaBin, "-cp", classpath, "com.codefit.testsupport.TwoProcessAutomaticSyncDemo",
                role, db.toString(), ownInvite.toString(), peerInvite.toString(), ownResult.toString(), peerResult.toString());
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        return builder.start();
    }

    private static String readResultOrEmpty(Path resultFile) {
        try {
            return Files.exists(resultFile) ? Files.readString(resultFile) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
