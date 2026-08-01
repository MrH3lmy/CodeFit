package com.codefit.controller;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.JavaTestCase;
import com.codefit.model.Problem;
import com.codefit.service.CompileOutcome;
import com.codefit.service.JavaExecutionCoordinator;
import com.codefit.service.JavaSolutionWorkspaceService;
import com.codefit.service.ProblemService;
import com.codefit.service.RunCancellationToken;
import com.codefit.ui.FxToolkitSupport;
import com.codefit.ui.NavigationService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression coverage for the #163 review findings: every Java run type (quick Run, a single test
 * case, Run All Test Cases) now routes through the shared, application-wide
 * {@link JavaExecutionCoordinator} via {@code beginJavaOperation}/{@code finishJavaOperationUi}, so
 * only one can ever be in flight, Cancel always cancels the right one, and navigation/compile can't
 * race a background run that's still reading the live {@link CompileOutcome}'s temp directory. Private
 * controller state is exercised via reflection, the same way
 * {@code SettingsControllerImportBusyStateTest} covers the import busy-state guard, since native
 * background-thread-driven flows can't be scripted directly.
 */
class ProblemSolvingWorkspaceJavaRunnerBusyStateTest {

    @BeforeAll
    static void requireFxToolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable(), "JavaFX toolkit unavailable (no display) - skipping controller UI-state checks");
        DatabaseConfig.initialize();
    }

    /** {@link JavaExecutionCoordinator} is a single process-wide static — a test that begins an
     *  operation (directly or via the real background-thread run methods) and doesn't itself release
     *  it would otherwise leave every later test unable to acquire the coordinator at all. */
    @AfterEach
    void releaseAnyStillActiveOperation() {
        JavaExecutionCoordinator.cancelActiveAndAwait(5, TimeUnit.SECONDS);
    }

    private ProblemSolvingWorkspaceController loadController() throws Exception {
        ProblemService problemService = new ProblemService();
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM",
                "TF-163-BUSY-" + UUID.randomUUID(), "Java Runner Busy State Fixture", null, "General", null, List.of());
        setPendingWorkspaceProblemId(problem.getId());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ProblemSolvingWorkspaceController> controllerRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/problem-solving-workspace.fxml"));
                Parent root = loader.load();
                if (root == null) {
                    throw new IllegalStateException("FXMLLoader returned a null root");
                }
                controllerRef.set(loader.getController());
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading problem-solving-workspace.fxml");
        }
        if (failure.get() != null) {
            fail("Failed to load problem-solving-workspace.fxml", failure.get());
        }
        return controllerRef.get();
    }

    @Test
    void beginningAnOperationDisablesCompileRunAndTestCaseControlsAndShowsCancel() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        runOnFxThreadAndWait(() -> {
            // JavaExecutionCoordinator only ever allows one active operation process-wide; a leaked
            // operation (e.g. from an assertion throwing before a trailing release call) would starve
            // every later test, so every begin in this class is matched with a try/finally release
            // that closes the actual Operation object (see endOperation).
            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, new RunCancellationToken(), "Running…");
            try {
                assertTrue(readButton(controller, "javaCompileButton").isDisabled(), "Compile must be disabled while an operation is active");
                assertTrue(readButton(controller, "javaRunButton").isDisabled(), "Run must be disabled while an operation is active");
                assertTrue(((VBox) readField(controller, "javaTestCasesBox")).isDisabled(), "test case controls must be disabled while an operation is active");
                assertTrue(readButton(controller, "javaCancelButton").isVisible(), "Cancel must become visible for a cancellable operation");
            } finally {
                endOperation(controller, operation);
            }
        });
    }

    @Test
    void aNonCancellableOperationLikeCompileNeverShowsCancel() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        runOnFxThreadAndWait(() -> {
            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, null, "Compiling…");
            try {
                assertTrue(readButton(controller, "javaCompileButton").isDisabled());
                assertFalse(readButton(controller, "javaCancelButton").isVisible(), "compile passes no token, so there is nothing Cancel could do");
            } finally {
                endOperation(controller, operation);
            }
        });
    }

    @Test
    void endingAnOperationReEnablesControlsAndHidesCancel() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        runOnFxThreadAndWait(() -> {
            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, new RunCancellationToken(), "Running…");
            endOperation(controller, operation);

            assertFalse(readButton(controller, "javaCompileButton").isDisabled());
            assertFalse(((VBox) readField(controller, "javaTestCasesBox")).isDisabled());
            assertFalse(readButton(controller, "javaCancelButton").isVisible());
        });
    }

    @Test
    void cancelJavaRunCancelsTheActiveTokenAndReportsCancelling() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        runOnFxThreadAndWait(() -> {
            RunCancellationToken token = new RunCancellationToken();
            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, token, "Running…");
            try {
                controller.cancelJavaRun();

                assertTrue(token.isCancelled(), "Cancel must cancel whatever token the active operation started with");
                assertEquals("Cancelling…", readLabel(controller, "javaRunStatusLabel").getText());
            } finally {
                endOperation(controller, operation);
            }
        });
    }

    @Test
    void goingToProblemsWhileAnOperationIsActiveIsRefusedAndTheLiveCompileOutcomeSurvives() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        long problemId = readProblemId(controller);

        runOnFxThreadAndWait(() -> {
            TextField classNameField = (TextField) readField(controller, "javaClassNameField");
            TextArea sourceArea = (TextArea) readField(controller, "javaSourceArea");
            classNameField.setText("Solution");
            sourceArea.setText("public class Solution {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(\"ok\");\n"
                    + "    }\n"
                    + "}\n");
            controller.compileJavaSolution();
        });
        waitUntil(() -> Boolean.FALSE.equals(readButtonDisabledOffFxThread(controller, "javaRunButton")), 15,
                "the trivial fixture program to finish compiling");

        runOnFxThreadAndWait(() -> {
            CompileOutcome liveOutcome = (CompileOutcome) readField(controller, "currentCompileOutcome");
            Path liveWorkDir = readWorkDir(liveOutcome);
            assertTrue(Files.exists(liveWorkDir), "sanity check: the compiled outcome's temp dir must exist before the guard is even exercised");

            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, new RunCancellationToken(), "Running…");
            try {
                // goProblems() would normally call NavigationService.showProblems(), which throws
                // outside a real application (no primary Stage configured in this test) - reaching
                // that call at all is exactly the bug this guard prevents, so a thrown
                // IllegalStateException here would mean the guard did NOT stop navigation.
                controller.goProblems();

                assertEquals("Wait for the current Java operation to finish, or cancel it, before leaving this workspace.",
                        readLabel(controller, "javaRunStatusLabel").getText());
                assertTrue(Files.exists(liveWorkDir),
                        "the refused navigation must not have let CompileOutcomeRegistry.closeCurrent() delete the live lease's temp dir");
            } finally {
                endOperation(controller, operation);
            }
        });

        assertEquals((Long) problemId, readProblemId(controller));
    }

    @Test
    void addingATestCaseWhileAnOperationIsActiveIsIgnored() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        runOnFxThreadAndWait(() -> {
            JavaExecutionCoordinator.Operation operation = invokeBeginJavaOperation(controller, new RunCancellationToken(), "Running…");
            try {
                int before = readTestCaseCount(controller);

                controller.addJavaTestCase();

                assertEquals(before, readTestCaseCount(controller), "addJavaTestCase must be a no-op while an operation is active");
            } finally {
                endOperation(controller, operation);
            }
        });
    }

    @Test
    void runAllTestCasesPersistsWhateverIsCurrentlyTypedRatherThanReadingAStaleDatabaseValue() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        long problemId = readProblemId(controller);

        runOnFxThreadAndWait(() -> {
            TextField classNameField = (TextField) readField(controller, "javaClassNameField");
            TextArea sourceArea = (TextArea) readField(controller, "javaSourceArea");
            classNameField.setText("Solution");
            sourceArea.setText("public class Solution {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(\"ok\");\n"
                    + "    }\n"
                    + "}\n");
            controller.compileJavaSolution();
        });

        // Compiling runs on its own background thread (java-runner-compile); wait for it to finish
        // outside the FX-thread block above, the same way JavaCodeRunnerTest waits for background work.
        waitUntil(() -> Boolean.FALSE.equals(readButtonDisabledOffFxThread(controller, "javaRunButton")), 15,
                "the trivial fixture program to finish compiling");

        runOnFxThreadAndWait(() -> {
            controller.addJavaTestCase();
            List<?> rows = (List<?>) readField(controller, "renderedTestCases");
            assertEquals(1, rows.size());
            Object rowState = rows.get(0);
            TextArea stdinArea = (TextArea) readRecordComponent(rowState, "stdinArea");
            TextArea expectedArea = (TextArea) readRecordComponent(rowState, "expectedArea");

            // Simulates a learner who just typed into the field and clicked "Run All Test Cases"
            // immediately, without tabbing/clicking away first — setText() alone never fires the
            // focus-loss listener that autosaves on blur, so the database still has the blank values
            // addJavaTestCase() created until something else persists them.
            stdinArea.setText("unsaved stdin");
            expectedArea.setText("unsaved expected");

            JavaSolutionWorkspaceService freshRead = new JavaSolutionWorkspaceService();
            JavaTestCase beforeRunAll = freshRead.listTestCases(problemId).get(0);
            assertEquals("", beforeRunAll.getStdin() == null ? "" : beforeRunAll.getStdin(),
                    "sanity check: the database must still hold the pre-edit blank value at this point");

            controller.runAllJavaTestCases();

            // runAllJavaTestCases() persists every row synchronously, on this thread, before it ever
            // spawns the background run thread - so the database must already reflect the edit the
            // instant the call returns, not eventually once some background thread gets to it.
            JavaTestCase afterRunAllReturns = freshRead.listTestCases(problemId).get(0);
            assertEquals("unsaved stdin", afterRunAllReturns.getStdin());
            assertEquals("unsaved expected", afterRunAllReturns.getExpectedOutput());
        });

        // runAllJavaTestCases() already started a real background run of its own (production code,
        // not a reflection-driven begin) - let it actually finish and release the coordinator itself,
        // rather than racing it with a manual finishJavaOperationUi() call that could double-close the
        // same Operation the background thread is still using.
        waitUntil(() -> readFieldOffFxThread(controller, "currentJavaOperation") == null, 15,
                "the real Run All Test Cases execution to finish and release the coordinator");
    }

    @Test
    void cancellingMidBatchStopsTheRemainingQueuedTestCases() throws Exception {
        ProblemSolvingWorkspaceController controller = loadController();
        long problemId = readProblemId(controller);

        runOnFxThreadAndWait(() -> {
            TextField classNameField = (TextField) readField(controller, "javaClassNameField");
            TextArea sourceArea = (TextArea) readField(controller, "javaSourceArea");
            classNameField.setText("Solution");
            // Sleeps long enough per run that the test can reliably cancel after the first case has
            // started but before the batch would otherwise finish on its own.
            sourceArea.setText("public class Solution {\n"
                    + "    public static void main(String[] args) throws Exception {\n"
                    + "        Thread.sleep(2000);\n"
                    + "        System.out.println(\"ok\");\n"
                    + "    }\n"
                    + "}\n");
            controller.compileJavaSolution();
        });
        waitUntil(() -> Boolean.FALSE.equals(readButtonDisabledOffFxThread(controller, "javaRunButton")), 15,
                "the slow fixture program to finish compiling");

        JavaSolutionWorkspaceService service = new JavaSolutionWorkspaceService();
        service.addTestCase(problemId);
        service.addTestCase(problemId);
        service.addTestCase(problemId);

        runOnFxThreadAndWait(() -> {
            invokeNoArg(controller, "renderTestCases");
            assertEquals(3, readTestCaseCount(controller));
            controller.runAllJavaTestCases();
        });

        // beginJavaOperation() (called synchronously by runAllJavaTestCases() before it spawns the
        // background thread) has already run by the time the call above returns, so the shared
        // cancellation token is already in place - cancelling right away reliably lands mid-batch,
        // either before the first case starts or while it's still running its 2-second sleep, well
        // before any of the three 2-second cases could finish on their own.
        runOnFxThreadAndWait(() -> controller.cancelJavaRun());

        waitUntil(() -> Boolean.TRUE.equals(readFieldOffFxThread(controller, "currentJavaOperation") == null),
                15, "the cancelled batch to finish releasing the coordinator");

        runOnFxThreadAndWait(() -> {
            String status = readLabel(controller, "javaRunStatusLabel").getText();
            assertTrue(status.contains("cancelled"), "final status must acknowledge the cancellation: was \"" + status + "\"");
            assertTrue(status.contains("cancelled after 0 completed") || status.contains("cancelled after 1 completed")
                            || status.contains("cancelled after 2 completed"),
                    "cancelling this early must leave at least one of the three queued cases unfinished: was \"" + status + "\"");

            // A case that was still running when cancelled ends up "Cancelled." by its own RunResult;
            // one that the loop never reached in time is left exactly where it started, "Queued…" -
            // either way, no case past the completed count above can show a real exit-status/PASS/FAIL
            // description, since the loop breaks the instant it sees a cancelled token or result.
            List<?> rows = (List<?>) readField(controller, "renderedTestCases");
            boolean anyNeverRanToCompletion = rows.stream().anyMatch(row -> {
                Label label = (Label) readRecordComponent(row, "resultLabel");
                String text = label.getText();
                return "Queued…".equals(text) || text.startsWith("Cancelled");
            });
            assertTrue(anyNeverRanToCompletion, "at least one queued test case must never have run to completion once the batch was cancelled");
        });
    }

    // --- reflection plumbing (mirrors SettingsControllerImportBusyStateTest) -------------------

    private long readProblemId(ProblemSolvingWorkspaceController controller) {
        return (long) (Long) readField(controller, "problemId");
    }

    private Path readWorkDir(CompileOutcome outcome) {
        try {
            Method method = CompileOutcome.class.getDeclaredMethod("workDir");
            method.setAccessible(true);
            return (Path) method.invoke(outcome);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private Boolean readButtonDisabledOffFxThread(ProblemSolvingWorkspaceController controller, String fieldName) {
        AtomicReference<Boolean> disabled = new AtomicReference<>();
        try {
            runOnFxThreadAndWait(() -> disabled.set(readButton(controller, fieldName).isDisabled()));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        return disabled.get();
    }

    private Object readFieldOffFxThread(ProblemSolvingWorkspaceController controller, String fieldName) {
        AtomicReference<Object> value = new AtomicReference<>();
        try {
            runOnFxThreadAndWait(() -> value.set(readField(controller, fieldName)));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        return value.get();
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, int timeoutSeconds, String description) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + description);
            }
        }
        fail("Timed out waiting for " + description);
    }

    private Object readRecordComponent(Object record, String componentName) {
        try {
            Method accessor = record.getClass().getDeclaredMethod(componentName);
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void invokeNoArg(ProblemSolvingWorkspaceController controller, String methodName) {
        try {
            Method method = ProblemSolvingWorkspaceController.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(controller);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * {@code beginJavaOperation} returns the actual {@link JavaExecutionCoordinator.Operation} it
     * acquired; {@code finishJavaOperationUi()} only resets the controller's own UI state (buttons,
     * labels) and does NOT release the coordinator's process-wide lock — production code always closes
     * the returned {@code Operation} itself (on the background thread, right before handing control
     * back to {@code finishJavaOperationUi()} via {@code Platform.runLater}). A test that begins one
     * via reflection and only calls {@code finishJavaOperationUi()} would leave the coordinator
     * permanently locked for every later test, so {@link #endOperation} always closes the real
     * {@code Operation} object first.
     */
    private JavaExecutionCoordinator.Operation invokeBeginJavaOperation(ProblemSolvingWorkspaceController controller, RunCancellationToken token, String status) {
        try {
            Method method = ProblemSolvingWorkspaceController.class.getDeclaredMethod(
                    "beginJavaOperation", RunCancellationToken.class, String.class);
            method.setAccessible(true);
            return (JavaExecutionCoordinator.Operation) method.invoke(controller, token, status);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void endOperation(ProblemSolvingWorkspaceController controller, JavaExecutionCoordinator.Operation operation) {
        operation.close();
        invokeNoArg(controller, "finishJavaOperationUi");
    }

    private Button readButton(ProblemSolvingWorkspaceController controller, String fieldName) {
        return (Button) readField(controller, fieldName);
    }

    private Label readLabel(ProblemSolvingWorkspaceController controller, String fieldName) {
        return (Label) readField(controller, fieldName);
    }

    @SuppressWarnings("unchecked")
    private int readTestCaseCount(ProblemSolvingWorkspaceController controller) {
        return ((List<Object>) readField(controller, "renderedTestCases")).size();
    }

    private Object readField(ProblemSolvingWorkspaceController controller, String fieldName) {
        try {
            Field field = ProblemSolvingWorkspaceController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(controller);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void setPendingWorkspaceProblemId(long problemId) throws ReflectiveOperationException {
        Field field = NavigationService.class.getDeclaredField("pendingWorkspaceProblemId");
        field.setAccessible(true);
        field.set(null, problemId);
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
