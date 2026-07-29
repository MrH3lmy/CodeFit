package com.codefit.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single, application-owned executor every workbook analyze/import background task runs on
 * (#160). Its worker thread is deliberately non-daemon: a daemon thread can be killed mid-transaction
 * the instant the JVM decides to exit, with no warning and no chance to finish or roll back cleanly.
 * An active database import must survive on its own terms, not "as long as the JVM happened to still
 * be running" — so this class, not the JavaFX {@code Application} lifecycle, owns the worker thread,
 * and {@link #shutdown} is the only sanctioned way to stop it.
 *
 * <p>{@link #markImportActive} tracks the number of reserved transactional import operations, including
 * imports queued behind another import on the single worker. A counter is used instead of one boolean
 * because Settings routes can be recreated while an earlier controller still owns an import. One
 * controller finishing must never clear the application-wide active state while another confirmed
 * import is queued or running. Analysis is read-only and never reserves an import slot.
 */
public final class BackgroundImportExecutor {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "training-sheet-import-worker");
        thread.setDaemon(false);
        return thread;
    });

    private static final AtomicInteger IMPORT_RESERVATIONS = new AtomicInteger(0);

    private BackgroundImportExecutor() {
    }

    /** Runs {@code task} on the shared, non-daemon worker thread. */
    public static void submit(Runnable task) {
        EXECUTOR.execute(task);
    }

    /**
     * Reserves or releases one real database-import operation. Calls must be paired by the controller's
     * success/failure/cancellation paths. Multiple reservations can exist when separate Settings
     * controller instances confirm imports before the single worker reaches them; the application stays
     * in the active-import state until the final reservation is released.
     */
    public static void markImportActive(boolean active) {
        if (active) {
            IMPORT_RESERVATIONS.incrementAndGet();
            return;
        }
        IMPORT_RESERVATIONS.updateAndGet(current -> Math.max(0, current - 1));
    }

    public static boolean hasActiveImport() {
        return IMPORT_RESERVATIONS.get() > 0;
    }

    static int activeImportReservations() {
        return IMPORT_RESERVATIONS.get();
    }

    /**
     * Stops accepting new work and waits up to {@code timeout} for whatever's already running to
     * finish on its own — the controlled path for an import that's already committing or rolling back.
     * Only if it doesn't finish in time does this force-cancel via {@link ExecutorService#shutdownNow()}
     * (which interrupts the worker thread; a blocking JDBC call may or may not honor that, but it's the
     * standard best-effort {@code ExecutorService} shutdown contract, not a guarantee of an instant,
     * clean abort).
     *
     * @return {@code true} if the executor terminated within {@code timeout}, {@code false} if it had
     *         to be force-cancelled
     */
    public static boolean shutdown(long timeout, TimeUnit unit) {
        return awaitThenForceShutdown(EXECUTOR, timeout, unit);
    }

    /** The graceful-then-forceful shutdown sequence itself, extracted so a test can exercise it
     *  against a disposable {@link ExecutorService} instead of the shared singleton {@link #EXECUTOR} —
     *  shutting down the real one mid-suite would leave every later test unable to submit work at all. */
    static boolean awaitThenForceShutdown(ExecutorService executor, long timeout, TimeUnit unit) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout, unit)) {
                return true;
            }
            executor.shutdownNow();
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return false;
        }
    }
}
