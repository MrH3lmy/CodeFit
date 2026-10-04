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
 *
 * <p>Each child process's stdout/stderr is redirected to its own log file (never {@code
 * .inheritIO()}, which would interleave both processes' output into this test's own stream with no
 * attribution) and printed on any failure, so a failure here is diagnosable from this test's own
 * output alone. A process that does not finish within its own wait window is forcibly destroyed
 * before this test returns - a timed-out child must never be left running in the background for a
 * later test (or a later run of this one) to contend with.
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
        Path logA = workDir.resolve("a-stdio.log");
        Path logB = workDir.resolve("b-stdio.log");

        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";

        // The listener writes its own invitation first, with no peer invitation to wait for yet.
        Process listenerProcess = startProcess(javaBin, classpath, "LISTENER", dbA, inviteA, inviteB, resultA, resultB, logA);
        Process dialerProcess = startProcess(javaBin, classpath, "DIALER", dbB, inviteB, inviteA, resultB, resultA, logB);

        try {
            boolean listenerFinished = listenerProcess.waitFor(80, TimeUnit.SECONDS);
            boolean dialerFinished = dialerProcess.waitFor(80, TimeUnit.SECONDS);

            String listenerResult = readResultOrEmpty(resultA);
            String dialerResult = readResultOrEmpty(resultB);

            if (!listenerFinished || !dialerFinished || !listenerResult.startsWith("SUCCESS") || !dialerResult.startsWith("SUCCESS")) {
                dumpDiagnostics(listenerFinished, dialerFinished, listenerResult, dialerResult, logA, logB);
            }

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
        } finally {
            // A process that already exited is a no-op to destroy; one that timed out above must never
            // be left running past this test's own return.
            listenerProcess.destroyForcibly();
            dialerProcess.destroyForcibly();
        }
    }

    private static Process startProcess(String javaBin, String classpath, String role, Path db, Path ownInvite,
                                         Path peerInvite, Path ownResult, Path peerResult, Path logFile) throws IOException {
        List<String> command = List.of(javaBin, "-cp", classpath, "com.codefit.testsupport.TwoProcessAutomaticSyncDemo",
                role, db.toString(), ownInvite.toString(), peerInvite.toString(), ownResult.toString(), peerResult.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true);
        return builder.start();
    }

    private static String readResultOrEmpty(Path resultFile) {
        try {
            return Files.exists(resultFile) ? Files.readString(resultFile) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String readLogOrEmpty(Path logFile) {
        try {
            return Files.exists(logFile) ? Files.readString(logFile) : "(no output captured)";
        } catch (IOException e) {
            return "(failed to read log: " + e + ")";
        }
    }

    private static void dumpDiagnostics(boolean listenerFinished, boolean dialerFinished, String listenerResult,
                                         String dialerResult, Path logA, Path logB) {
        System.err.println("=== TwoProcessAutomaticSyncDemoTest failure diagnostics ===");
        System.err.println("listenerFinished=" + listenerFinished + " dialerFinished=" + dialerFinished);
        System.err.println("listenerResult=" + listenerResult);
        System.err.println("dialerResult=" + dialerResult);
        System.err.println("--- listener (A) stdout/stderr ---");
        System.err.println(readLogOrEmpty(logA));
        System.err.println("--- dialer (B) stdout/stderr ---");
        System.err.println(readLogOrEmpty(logB));
        System.err.println("=== end diagnostics ===");
    }
}
