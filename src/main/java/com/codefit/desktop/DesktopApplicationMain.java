package com.codefit.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.*;

/** Experimental headless owner. The parent supplies one dedicated profile; the renderer cannot. */
public final class DesktopApplicationMain {
    private DesktopApplicationMain() {}

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21
                || args.length != 4
                || !"--parent".equals(args[0])
                || !"--experimental-profile".equals(args[2])) {
            System.err.println("DESKTOP_INVALID_STARTUP");
            System.exit(2);
            return;
        }
        ProcessHandle parent;
        try {
            parent = ProcessHandle.current().parent().orElseThrow();
            if (parent.pid() != Long.parseLong(args[1]) || !parent.isAlive())
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            System.err.println("DESKTOP_INVALID_PARENT");
            System.exit(2);
            return;
        }
        ScheduledExecutorService watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "desktop-parent-watchdog");
                            t.setDaemon(true);
                            return t;
                        });
        // OS closes the profile lock; SQLite rolls back an unfinished transaction on next startup.
        // No shutdown hook can release ownership while another callback is still writing.
        watchdog.scheduleWithFixedDelay(
                () -> {
                    if (!parent.isAlive()) Runtime.getRuntime().halt(0);
                },
                200,
                200,
                TimeUnit.MILLISECONDS);
        boolean ready = false;
        try (ExperimentalProfile profile = ExperimentalProfile.open(Path.of(args[3]))) {
            DesktopApplicationService application = new DesktopApplicationService(profile);
            ApplicationProtocol.write(System.out, ApplicationProtocol.ready(profile.id()));
            ready = true;
            byte[] frame;
            while ((frame = ApplicationProtocol.readFrame(System.in)) != null) {
                ApplicationProtocol.Request request = ApplicationProtocol.parse(frame);
                byte[] response;
                try {
                    Object result =
                            switch (request.method()) {
                                case "state.read" -> application.readState(request.afterId());
                                case "commands.status" ->
                                        application.status(
                                                request.profileId(), request.commandId());
                                case "decks.create" ->
                                        application.create(
                                                request.profileId(),
                                                request.commandId(),
                                                request.name(),
                                                request.description());
                                default -> throw new IllegalArgumentException("INVALID_INPUT");
                            };
                    response = ApplicationProtocol.result(request.id(), result);
                } catch (IllegalArgumentException invalid) {
                    String code =
                            Set.of("PROFILE_MISMATCH", "COMMAND_ID_CONFLICT", "INVALID_INPUT")
                                            .contains(invalid.getMessage())
                                    ? invalid.getMessage()
                                    : "INVALID_INPUT";
                    response = ApplicationProtocol.failed(request.id(), code);
                } catch (SQLException | RuntimeException | IOException failure) {
                    response =
                            ApplicationProtocol.failed(
                                    request.id(),
                                    request.method().equals("decks.create")
                                            ? "COMMAND_FAILED"
                                            : "READ_FAILED");
                }
                ApplicationProtocol.write(System.out, response);
            }
        } catch (Exception failure) {
            if (!ready) {
                try {
                    ApplicationProtocol.write(
                            System.out,
                            ApplicationProtocol.startupFailed(
                                    "PROFILE_BUSY".equals(failure.getMessage())
                                            ? "PROFILE_BUSY"
                                            : "PROFILE_INVALID"));
                } catch (IOException ignored) {
                    /* broken parent pipe */
                }
            }
            System.err.println("DESKTOP_SESSION_CLOSED");
            System.exit(1);
        } finally {
            watchdog.shutdownNow();
        }
    }
}
