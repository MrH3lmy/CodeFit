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
 * The literal #182 acceptance criterion: "Demonstrate two independent processes with separate databases
 * and keys exchanging protocol messages over real sockets." Launches {@link TwoProcessPeerDemo} twice as
 * genuinely separate OS processes (separate JVMs), each against its own throwaway SQLite file and its
 * own freshly generated identity/transport keys, exchanging invitations through plain files (the
 * out-of-band channel a real user would use — a copied string or a file — not a shared database or
 * in-process object), then completing a real mutual-TLS handshake between the two processes' own
 * listeners.
 */
class TwoProcessPeerDemoTest {

    @Test
    @Timeout(60)
    void twoSeparateProcessesWithSeparateDatabasesPairAndConnectOverRealSockets() throws Exception {
        Path workDir = Files.createTempDirectory("codefit-two-process-demo");
        Path dbA = workDir.resolve("a.db");
        Path dbB = workDir.resolve("b.db");
        Path inviteA = workDir.resolve("a-invite.txt");
        Path inviteB = workDir.resolve("b-invite.txt");
        Path resultA = workDir.resolve("a-result.txt");
        Path resultB = workDir.resolve("b-result.txt");

        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";

        // The listener writes its own invitation first, with no peer invitation to wait for yet.
        Process listenerProcess = startProcess(javaBin, classpath, "LISTENER", dbA, inviteA, inviteB, resultA);
        Process dialerProcess = startProcess(javaBin, classpath, "DIALER", dbB, inviteB, inviteA, resultB);

        boolean listenerFinished = listenerProcess.waitFor(50, TimeUnit.SECONDS);
        boolean dialerFinished = dialerProcess.waitFor(50, TimeUnit.SECONDS);

        String listenerResult = readResultOrEmpty(resultA);
        String dialerResult = readResultOrEmpty(resultB);

        assertTrue(listenerFinished, "listener process did not finish in time. result=" + listenerResult);
        assertTrue(dialerFinished, "dialer process did not finish in time. result=" + dialerResult);
        assertTrue(listenerResult.startsWith("SUCCESS"), "listener (process A) result: " + listenerResult);
        assertTrue(dialerResult.startsWith("SUCCESS"), "dialer (process B) result: " + dialerResult);
        assertEquals(0, listenerProcess.exitValue(), "listener process exit code; result=" + listenerResult);
        assertEquals(0, dialerProcess.exitValue(), "dialer process exit code; result=" + dialerResult);

        // Independent databases: two distinct, non-empty SQLite files, never touched by the other process.
        assertTrue(Files.size(dbA) > 0);
        assertTrue(Files.size(dbB) > 0);
    }

    private static Process startProcess(String javaBin, String classpath, String role, Path db, Path ownInvite,
                                         Path peerInvite, Path result) throws IOException {
        List<String> command = List.of(javaBin, "-cp", classpath, "com.codefit.testsupport.TwoProcessPeerDemo",
                role, db.toString(), ownInvite.toString(), peerInvite.toString(), result.toString());
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
