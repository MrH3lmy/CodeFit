package com.codefit.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single, application-owned executor every workbook analyze/import background task runs on
 * (#160). Its worker thread is deliberately non-daemon: a daemon thread can be killed mid-transaction
 * the instant the JVM decides to exit, with no warning and no chance to finish or roll back cleanly.
 * An active database import must survive on its own terms, not "as long as the JVM happened to still
 * be running" — so this class, not the JavaFX {@code Application} lifecycle, owns the worker thread,
 * and {@link #shutdown} is the only sanctioned way to stop it.
 *
 * <p>{@link #markImportActive} tracks only the transactional (write) phase of an import — analysis is
 * read-only, bounded, and safe to simply abandon, so it never sets this. {@code CodeFitApplication#stop()}
 * calls {@link #shutdown} on normal application exit; {@code NavigationService} checks
 * {@link #hasActiveImport()} before letting the primary window's close request through, so a learner
 * gets a chance to confirm rather than silently losing an in-flight import.
 */
public final class BackgroundImportExecutor {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "training-sheet-import-worker");
        thread.setDaemon(false);
        return thread;
    });

    private static final AtomicBoolean IMPORT_ACTIVE = new AtomicBoolean(false);

    private BackgroundImportExecutor() {
    }

    /** Runs {@code task} on the shared, non-daemon worker thread. */
    public static void submit(Runnable task) {
        EXECUTOR.execute(task);
    }

    /** Marks whether a real (write-to-the-database) import is currently running. Analysis never calls
     *  this — only the transactional import phase does, in a {@code try}/{@code finally} around it. */
    public static void markImportActive(boolean active) {
        IMPORT_ACTIVE.set(active);
    }

    public static boolean hasActiveImport() {
        return IMPORT_ACTIVE.get();
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
