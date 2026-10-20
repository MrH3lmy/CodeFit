package com.codefit.desktop;

import com.codefit.service.DeckService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Experimental headless entrypoint. It cannot select a learner profile or execute mutations. */
public final class DesktopReadOnlyMain {
    private DesktopReadOnlyMain() {}

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21 || args.length != 2 || !"--parent".equals(args[0])) {
            System.err.println("GATE0_INVALID_STARTUP");
            System.exit(2);
        }
        ProcessHandle parent;
        try {
            parent = ProcessHandle.current().parent().orElseThrow();
            if (parent.pid() != Long.parseLong(args[1]) || !parent.isAlive())
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            System.err.println("GATE0_INVALID_PARENT");
            System.exit(2);
            return;
        }
        // Parent loss while blocked on stdin or a database read must not leave an orphan engine.
        ScheduledExecutorService watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "gate0-parent-watchdog");
                            thread.setDaemon(true);
                            return thread;
                        });
        watchdog.scheduleWithFixedDelay(
                () -> {
                    if (!parent.isAlive()) System.exit(0);
                },
                200,
                200,
                TimeUnit.MILLISECONDS);
        try (IsolatedDesktopDatabase database = new IsolatedDesktopDatabase()) {
            Thread cleanup = new Thread(database::close, "gate0-database-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                database.initialize();
                DesktopProtocol.write(
                        System.out,
                        "{\"v\":1,\"type\":\"ready\"}".getBytes(StandardCharsets.UTF_8));
                DeckService decks = new DeckService();
                byte[] frame;
                while ((frame = DesktopProtocol.readFrame(System.in)) != null) {
                    var request = DesktopProtocol.parse(frame);
                    byte[] response;
                    try {
                        response =
                                DesktopProtocol.decks(request.id(), database.read(decks::getDecks));
                    } catch (RuntimeException | IOException failure) {
                        System.err.println("GATE0_READ_FAILED");
                        response = DesktopProtocol.failed(request.id());
                    }
                    DesktopProtocol.write(System.out, response);
                }
            } finally {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            }
        } catch (Exception failure) {
            System.err.println("GATE0_SESSION_FAILED");
            System.exit(1);
        } finally {
            watchdog.shutdownNow();
        }
    }
}
