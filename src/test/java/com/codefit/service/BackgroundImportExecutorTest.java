package com.codefit.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #160 finding that an active database import must not depend on daemon-thread behavior:
 * {@link BackgroundImportExecutor}'s worker must be non-daemon, its active-import flag must be exactly
 * what a close-request handler checks, and its shutdown sequence must be a real graceful-then-forceful
 * {@link ExecutorService} shutdown. The shutdown assertions run against a disposable executor (via the
 * package-private {@link BackgroundImportExecutor#awaitThenForceShutdown}) rather than the shared
 * singleton, since shutting that down mid-suite would leave every later test unable to submit work.
 */
class BackgroundImportExecutorTest {

    @AfterEach
    void clearActiveFlag() {
        // BackgroundImportExecutor.markImportActive is shared, static, process-wide state; leaving it
        // set to true would make every later test's close-request behavior look like an import is
        // still running.
        BackgroundImportExecutor.markImportActive(false);
    }

    @Test
    void theSharedWorkerThreadIsNeverADaemonThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean wasDaemon = new AtomicBoolean(true);
        BackgroundImportExecutor.submit(() -> {
            wasDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "the submitted task must actually run");
        assertFalse(wasDaemon.get(), "an active database import must not run on a daemon thread");
    }

    @Test
    void markImportActiveIsExactlyWhatHasActiveImportReports() {
        assertFalse(BackgroundImportExecutor.hasActiveImport(), "no import is running at the start of this test");

        BackgroundImportExecutor.markImportActive(true);
        assertTrue(BackgroundImportExecutor.hasActiveImport());

        BackgroundImportExecutor.markImportActive(false);
        assertFalse(BackgroundImportExecutor.hasActiveImport());
    }

    @Test
    void shutdownGracefullyAwaitsAQuickTaskInsteadOfForceCancellingIt() throws Exception {
        ExecutorService disposableExecutor = Executors.newSingleThreadExecutor();
        AtomicBoolean completed = new AtomicBoolean(false);
        disposableExecutor.execute(() -> {
            try {
                Thread.sleep(50);
                completed.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        boolean terminatedGracefully = BackgroundImportExecutor.awaitThenForceShutdown(disposableExecutor, 5, TimeUnit.SECONDS);

        assertTrue(terminatedGracefully, "a task well within the timeout must be awaited, not force-cancelled");
        assertTrue(completed.get());
    }

    @Test
    void shutdownForceCancelsATaskThatOutlivesTheTimeout() throws Exception {
        ExecutorService disposableExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        disposableExecutor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        boolean terminatedGracefully = BackgroundImportExecutor.awaitThenForceShutdown(disposableExecutor, 200, TimeUnit.MILLISECONDS);

        assertFalse(terminatedGracefully, "a task that outlives the timeout must be force-cancelled, not awaited forever");
        assertTrue(disposableExecutor.isShutdown());
    }
}
