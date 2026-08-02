package com.codefit.ui;

import com.codefit.testsupport.IsolatedDatabaseExtension;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads every FXML resource the shell can reach through a real FXMLLoader so a broken fx:id,
 * missing controller method, or malformed FXML file fails a test before a PR is opened, rather
 * than only surfacing when someone runs the actual application.
 *
 * <p>Requires a JavaFX-capable display. Most CI runners today don't have one, so these tests skip
 * (not fail) via {@link Assumptions} when the toolkit can't start; they still run and protect
 * against regressions on any machine — including this project's own dev sandbox — that has one.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class FxmlLoadingTest {

    @BeforeAll
    static void requireFxToolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable(), "JavaFX toolkit unavailable (no display) - skipping FXML regression checks");
    }

    @Test
    void appShellLoads() throws Exception {
        assertLoads("/fxml/app-shell.fxml");
    }

    @Test
    void everyRouteFxmlLoads() throws Exception {
        for (Route route : Route.values()) {
            assertLoads(route.fxmlPath());
        }
    }

    private void assertLoads(String resourcePath) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Parent> loaded = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                loaded.set(FXMLLoader.load(getClass().getResource(resourcePath)));
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading " + resourcePath);
        }
        if (failure.get() != null) {
            fail("Failed to load " + resourcePath, failure.get());
        }
        assertNotNull(loaded.get(), "FXMLLoader returned null root for " + resourcePath);
    }
}
