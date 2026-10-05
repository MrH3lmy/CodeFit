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
 * The required product-level real-boundary proof for the 1-v-1 Study Match V1 milestone: two
 * genuinely separate OS processes pair, connect over real mutual-TLS transport, carry out the real
 * challenger-invites/opponent-accepts match lifecycle (converging on the identical matchId/
 * startedAt/endsAt), each contributes one real piece of study evidence, each approves and syncs
 * their own real match-window progress, and each observes the other's real synced progress through
 * the real {@code SnapshotComparisonEngine} - all driven by PR #198's own automatic connection-
 * established orchestration, with neither process ever calling {@code
 * PeerSyncSessionService.startReceiving}/{@code sendOutboxTo} directly. The match is proven to reach
 * a stable COMPLETED final result without either process waiting 15 real minutes - see {@code
 * TwoProcessStudyMatchDemo}'s own javadoc for exactly how.
 *
 * <p>Each child process's stdout/stderr is redirected to its own log file and printed on any
 * failure, so a failure here is diagnosable from this test's own output alone. A process that does
 * not finish within its own wait window is forcibly destroyed before this test returns.
 */
class TwoProcessStudyMatchDemoTest {

    @Test
    @Timeout(120)
    void twoSeparateProcessesCarryOutARealStudyMatchToACompletedStableFinalResult() throws Exception {
        Path workDir = Files.createTempDirectory("codefit-two-process-study-match-demo");
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

        // LISTENER is the challenger; DIALER is the opponent - the listener writes its own invitation
        // first, with no peer invitation to wait for yet.
        Process listenerProcess = startProcess(javaBin, classpath, "LISTENER", dbA, inviteA, inviteB, resultA, resultB, logA);
        Process dialerProcess = startProcess(javaBin, classpath, "DIALER", dbB, inviteB, inviteA, resultB, resultA, logB);

        try {
            boolean listenerFinished = listenerProcess.waitFor(110, TimeUnit.SECONDS);
            boolean dialerFinished = dialerProcess.waitFor(110, TimeUnit.SECONDS);

            String listenerResult = readResultOrEmpty(resultA);
            String dialerResult = readResultOrEmpty(resultB);

            if (!listenerFinished || !dialerFinished || !listenerResult.startsWith("SUCCESS") || !dialerResult.startsWith("SUCCESS")) {
                dumpDiagnostics(listenerFinished, dialerFinished, listenerResult, dialerResult, logA, logB);
            }

            assertTrue(listenerFinished, "challenger process did not finish in time. result=" + listenerResult);
            assertTrue(dialerFinished, "opponent process did not finish in time. result=" + dialerResult);
            assertTrue(listenerResult.startsWith("SUCCESS"),
                    "challenger (process A) never reached a stable COMPLETED match result: " + listenerResult);
            assertTrue(dialerResult.startsWith("SUCCESS"),
                    "opponent (process B) never reached a stable COMPLETED match result: " + dialerResult);
            assertTrue(listenerResult.contains("COMPLETED"), "challenger's final match status must be COMPLETED: " + listenerResult);
            assertTrue(dialerResult.contains("COMPLETED"), "opponent's final match status must be COMPLETED: " + dialerResult);
            assertEquals(0, listenerProcess.exitValue(), "challenger process exit code; result=" + listenerResult);
            assertEquals(0, dialerProcess.exitValue(), "opponent process exit code; result=" + dialerResult);

            assertTrue(Files.size(dbA) > 0);
            assertTrue(Files.size(dbB) > 0);
        } finally {
            listenerProcess.destroyForcibly();
            dialerProcess.destroyForcibly();
        }
    }

    private static Process startProcess(String javaBin, String classpath, String role, Path db, Path ownInvite,
                                         Path peerInvite, Path ownResult, Path peerResult, Path logFile) throws IOException {
        List<String> command = List.of(javaBin, "-cp", classpath, "com.codefit.testsupport.TwoProcessStudyMatchDemo",
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
        System.err.println("=== TwoProcessStudyMatchDemoTest failure diagnostics ===");
        System.err.println("listenerFinished=" + listenerFinished + " dialerFinished=" + dialerFinished);
        System.err.println("listenerResult=" + listenerResult);
        System.err.println("dialerResult=" + dialerResult);
        System.err.println("--- challenger (A) stdout/stderr ---");
        System.err.println(readLogOrEmpty(logA));
        System.err.println("--- opponent (B) stdout/stderr ---");
        System.err.println(readLogOrEmpty(logB));
        System.err.println("=== end diagnostics ===");
    }
}
