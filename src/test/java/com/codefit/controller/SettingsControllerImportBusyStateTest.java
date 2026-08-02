package com.codefit.controller;

import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.ui.FxToolkitSupport;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the #160 gap analysis' "background task and UI behavior" requirements against
 * {@link SettingsController}'s import flow, using reflection to reach {@code setImportBusy} and the
 * {@code importBusy} guard directly - the "extracted testable presentation logic" the review called
 * for - since the flow's entry point ({@code importTrainingSheet()}) opens a native, unscriptable
 * {@code FileChooser} that can't be driven headlessly in a test.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SettingsControllerImportBusyStateTest {

    @BeforeAll
    static void requireFxToolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable(), "JavaFX toolkit unavailable (no display) - skipping controller UI-state checks");
    }

    private SettingsController loadController() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<SettingsController> controllerRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/settings.fxml"));
                Parent root = loader.load();
                assertNotNullFxThread(root);
                controllerRef.set(loader.getController());
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading settings.fxml");
        }
        if (failure.get() != null) {
            fail("Failed to load settings.fxml", failure.get());
        }
        return controllerRef.get();
    }

    private void assertNotNullFxThread(Parent root) {
        if (root == null) {
            throw new IllegalStateException("FXMLLoader returned a null root");
        }
    }

    @Test
    void settingBusyDisablesTheButtonAndShowsTheStatusLabel() throws Exception {
        SettingsController controller = loadController(); // its own FX round-trip; must not run inside another
        runOnFxThreadAndWait(() -> {
            invokeSetImportBusy(controller, true, "Analyzing \"demo.xlsx\"…");
            assertTrue(readButton(controller).isDisabled(), "the Import Training Sheet button must be disabled while busy");
            Label statusLabel = readStatusLabel(controller);
            assertTrue(statusLabel.isVisible(), "the status label must be visible while busy");
            assertEquals("Analyzing \"demo.xlsx\"…", statusLabel.getText());
        });
    }

    @Test
    void clearingBusyReEnablesTheButtonAndHidesTheStatusLabel() throws Exception {
        SettingsController controller = loadController();
        runOnFxThreadAndWait(() -> {
            invokeSetImportBusy(controller, true, "Importing…");
            invokeSetImportBusy(controller, false, null);

            assertFalse(readButton(controller).isDisabled(), "the button must be re-enabled once busy clears");
            assertFalse(readStatusLabel(controller).isVisible(), "the status label must hide once busy clears");
        });
    }

    @Test
    void aSecondImportCannotStartWhileOneIsAlreadyInProgress() throws Exception {
        SettingsController controller = loadController();
        runOnFxThreadAndWait(() -> {
            invokeSetImportBusy(controller, true, "Importing \"demo.xlsx\"…");
            // Simulates a second click on "Import Training Sheet…" while the first analyze/import
            // cycle is still running: the importBusy guard at the top of importTrainingSheet() must
            // return immediately, before ever reaching the (unscriptable, native) FileChooser.
            invokePublicImportTrainingSheet(controller);

            assertTrue(readButton(controller).isDisabled(), "the second click must not have disturbed the still-running first import");
            assertEquals("Importing \"demo.xlsx\"…", readStatusLabel(controller).getText(),
                    "the status label from the first import must be untouched by the ignored second click");
        });
    }

    // --- reflection plumbing -------------------------------------------------------------------

    private void invokeSetImportBusy(SettingsController controller, boolean busy, String statusText) {
        try {
            Method method = SettingsController.class.getDeclaredMethod("setImportBusy", boolean.class, String.class);
            method.setAccessible(true);
            method.invoke(controller, busy, statusText);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void invokePublicImportTrainingSheet(SettingsController controller) {
        controller.importTrainingSheet();
    }

    private Button readButton(SettingsController controller) {
        return (Button) readField(controller, "importTrainingSheetButton");
    }

    private Label readStatusLabel(SettingsController controller) {
        return (Label) readField(controller, "importStatusLabel");
    }

    private Object readField(SettingsController controller, String fieldName) {
        try {
            Field field = SettingsController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(controller);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void runOnFxThreadAndWait(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out running on the JavaFX Application Thread");
        }
        if (failure.get() != null) {
            if (failure.get() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            fail(failure.get());
        }
    }
}
