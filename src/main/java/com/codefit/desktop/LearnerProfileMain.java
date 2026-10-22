package com.codefit.desktop;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Dedicated finite read capability. Explicit main-process selection is its only source authority.
 */
public final class LearnerProfileMain {
    private LearnerProfileMain() {}

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21
                || args.length != 8
                || !args[0].equals("--parent")
                || !args[2].equals("--source")
                || !args[4].equals("--snapshots")
                || !args[6].equals("--session")
                || !ApplicationProtocol.uuid(args[7])) {
            System.exit(2);
            return;
        }
        ProcessHandle parent;
        try {
            parent = ProcessHandle.current().parent().orElseThrow();
            if (parent.pid() != Long.parseLong(args[1]) || !parent.isAlive())
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            System.exit(2);
            return;
        }
        var watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "learner-snapshot-watchdog");
                            t.setDaemon(true);
                            return t;
                        });
        watchdog.scheduleWithFixedDelay(
                () -> {
                    if (!parent.isAlive()) Runtime.getRuntime().halt(0);
                },
                200,
                200,
                TimeUnit.MILLISECONDS);
        // JNI backup cannot be interrupted by ProgressObserver exceptions or sqlite3_interrupt.
        // Electron terminates earlier (20s + 1s escalation); this is a second hard bound.
        var startupDeadline =
                watchdog.schedule(() -> Runtime.getRuntime().halt(3), 25, TimeUnit.SECONDS);
        boolean ready = false;
        try (LearnerSnapshot snapshot =
                LearnerSnapshot.capture(Path.of(args[3]), Path.of(args[5]), args[7])) {
            startupDeadline.cancel(false);
            ApplicationProtocol.write(System.out, LearnerProtocol.ready(snapshot));
            ready = true;
            byte[] frame;
            while ((frame = LearnerProtocol.readFrame(System.in)) != null) {
                var request = LearnerProtocol.parse(frame);
                byte[] response;
                if (!request.profileId().equals(snapshot.profileId())
                        || !request.sessionId().equals(snapshot.sessionId()))
                    response = LearnerProtocol.failed(request.id(), "PROFILE_MISMATCH");
                else {
                    try {
                        response = LearnerProtocol.result(request.id(), snapshot.read());
                    } catch (Exception failure) {
                        response = LearnerProtocol.failed(request.id(), "READ_FAILED");
                    }
                }
                ApplicationProtocol.write(System.out, response);
            }
        } catch (Exception failure) {
            if (!ready) {
                String code =
                        Set.of(
                                                "PROFILE_BUSY",
                                                "PROFILE_INVALID",
                                                "INCOMPATIBLE_SCHEMA",
                                                "SOURCE_CHANGED",
                                                "SNAPSHOT_LIMIT",
                                                "SNAPSHOT_FAILED",
                                                "UNSUPPORTED_PLATFORM")
                                        .contains(String.valueOf(failure.getMessage()))
                                ? failure.getMessage()
                                : "PROFILE_INVALID";
                try {
                    ApplicationProtocol.write(System.out, LearnerProtocol.startupFailed(code));
                } catch (java.io.IOException ignored) {
                    /* parent gone */
                }
            }
            System.err.println("LEARNER_SESSION_CLOSED");
            System.exit(1);
        } finally {
            watchdog.shutdownNow();
        }
    }
}
