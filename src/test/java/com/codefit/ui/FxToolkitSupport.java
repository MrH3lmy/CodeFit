package com.codefit.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the JavaFX toolkit at most once per JVM for tests that need to load real FXML/Scene
 * graph nodes. JavaFX requires a windowing display even for off-screen use; environments without
 * one (most CI runners today) get {@code false} back so callers can skip via
 * {@code org.junit.jupiter.api.Assumptions.assumeTrue(...)} instead of failing the build. Public so
 * tests outside {@code com.codefit.ui} (e.g. controller tests) can reuse the same one-time startup.
 */
public final class FxToolkitSupport {
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);
    private static volatile boolean available;

    private FxToolkitSupport() {
    }

    public static boolean isAvailable() {
        if (ATTEMPTED.compareAndSet(false, true)) {
            available = tryStart();
        }
        return available;
    }

    private static boolean tryStart() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            return latch.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }
}
