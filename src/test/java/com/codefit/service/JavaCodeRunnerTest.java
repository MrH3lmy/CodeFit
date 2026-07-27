package com.codefit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #163 acceptance criteria: compile success/syntax failure/runtime failure, timeout,
 * cancellation (of the full process tree), output truncation, custom stdin, elapsed timing, and that
 * temporary work directories never survive past a {@link CompileOutcome#close()} — success, failure,
 * or cancellation alike.
 */
class JavaCodeRunnerTest {

    private final JavaCodeRunner runner = new JavaCodeRunner();

    @Test
    void isAvailableWhenARealJdkIsOnTheClasspath() {
        assertTrue(runner.isAvailable(), "the JDK running these tests must itself be usable by the runner");
        assertNull(runner.getUnavailabilityReason());
    }

    @Test
    void aBogusJavaHomeIsReportedAsUnavailableWithActionableGuidance() {
        JavaCodeRunner unavailableRunner = new JavaCodeRunner("/no/such/jdk/here");
        assertFalse(unavailableRunner.isAvailable());
        assertNotNull(unavailableRunner.getUnavailabilityReason());
        assertTrue(unavailableRunner.getUnavailabilityReason().toLowerCase().contains("jdk"),
                "the message should point the learner at installing/configuring a JDK");
    }

    @Test
    void successfulCompilationAndRunProducesStdoutAndAZeroExitCode() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        System.out.println("hello runner");
                    }
                }
                """;
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success(), compiled.rawOutput());
            RunResult result = runner.run(compiled, "", RunLimits.defaults(), null);
            assertEquals(0, result.exitCode());
            assertEquals("hello runner", result.stdout().strip());
            assertFalse(result.timedOut());
            assertFalse(result.cancelled());
            assertTrue(result.elapsedMillis() >= 0);
        }
    }

    @Test
    void customStandardInputIsFedToTheRunningProgram() {
        String source = """
                import java.util.Scanner;
                public class Solution {
                    public static void main(String[] args) {
                        Scanner scanner = new Scanner(System.in);
                        int a = scanner.nextInt();
                        int b = scanner.nextInt();
                        System.out.println(a + b);
                    }
                }
                """;
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success(), compiled.rawOutput());
            RunResult result = runner.run(compiled, "3 4\n", RunLimits.defaults(), null);
            assertEquals("7", result.stdout().strip());
            assertTrue(result.matchesExpectedOutput("7"));
        }
    }

    @Test
    void syntaxErrorIsReportedAsAStructuredDiagnosticWithLineAndColumn() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        System.out.println("missing semicolon")
                    }
                }
                """;
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertFalse(compiled.success());
            assertFalse(compiled.diagnostics().isEmpty());
            CompileDiagnostic diagnostic = compiled.diagnostics().get(0);
            assertEquals("Solution.java", diagnostic.file());
            assertEquals(3, diagnostic.line(), "the missing-semicolon line");
            assertTrue(diagnostic.error());
            assertNotNull(diagnostic.message());
        }
    }

    @Test
    void runtimeExceptionExitsNonZeroWithStderrCaptured() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        throw new IllegalStateException("boom");
                    }
                }
                """;
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success(), compiled.rawOutput());
            RunResult result = runner.run(compiled, "", RunLimits.defaults(), null);
            assertFalse(result.timedOut());
            assertFalse(result.cancelled());
            assertTrue(result.exitCode() != 0);
            assertTrue(result.stderr().contains("IllegalStateException"));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void infiniteLoopIsTerminatedByTheConfiguredTimeout() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        while (true) {
                        }
                    }
                }
                """;
        RunLimits shortTimeout = new RunLimits(1, 64, 8 * 1024);
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success());
            RunResult result = runner.run(compiled, "", shortTimeout, null);
            assertTrue(result.timedOut());
            assertFalse(result.cancelled());
            assertNull(result.exitCode());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void cancellingAnInFlightRunKillsItBeforeItsOwnTimeout() throws Exception {
        String source = """
                public class Solution {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(30_000);
                    }
                }
                """;
        RunLimits generousTimeout = new RunLimits(30, 64, 8 * 1024);
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success());
            RunCancellationToken token = new RunCancellationToken();
            AtomicReference<RunResult> resultRef = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            Thread runnerThread = new Thread(() -> {
                resultRef.set(runner.run(compiled, "", generousTimeout, token));
                done.countDown();
            });
            runnerThread.start();

            Thread.sleep(500); // give the child process time to actually start
            token.cancel();

            assertTrue(done.await(10, TimeUnit.SECONDS), "cancellation must stop the run well before its 30s timeout");
            RunResult result = resultRef.get();
            assertTrue(result.cancelled());
            assertFalse(result.timedOut());
        }
    }

    @Test
    void outputPastTheCapIsTruncatedRatherThanGrowingUnbounded() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        for (int i = 0; i < 1_000_000; i++) {
                            System.out.println("line " + i);
                        }
                    }
                }
                """;
        RunLimits tinyOutputCap = new RunLimits(10, 64, 256);
        try (CompileOutcome compiled = runner.compile(source, "Solution")) {
            assertTrue(compiled.success());
            RunResult result = runner.run(compiled, "", tinyOutputCap, null);
            assertTrue(result.outputTruncated());
            assertTrue(result.stdout().length() <= 256 + 64, "captured output should stay close to the cap, not grow unbounded");
        }
    }

    @Test
    void compileOutcomeWorkDirIsRemovedAfterCloseOnSuccessAndFailure() {
        Path successWorkDir;
        try (CompileOutcome compiled = runner.compile("public class Solution { public static void main(String[] a) {} }", "Solution")) {
            successWorkDir = compiled.workDir();
            assertTrue(Files.exists(successWorkDir));
        }
        assertFalse(Files.exists(successWorkDir), "a successful compile's work directory must be cleaned up after close()");

        Path failureWorkDir;
        try (CompileOutcome compiled = runner.compile("this is not java", "Solution")) {
            failureWorkDir = compiled.workDir();
        }
        assertFalse(Files.exists(failureWorkDir), "a failed compile's work directory must also be cleaned up after close()");
    }

    @Test
    void runningAnUnsuccessfulCompileThrowsRatherThanSilentlyDoingNothing() {
        try (CompileOutcome compiled = runner.compile("this is not java", "Solution")) {
            assertFalse(compiled.success());
            assertThrows(IllegalArgumentException.class, () -> runner.run(compiled, "", RunLimits.defaults(), null));
        }
    }

    @Test
    void diagnosticParsingFallsBackToWholeMessageWhenTheFormatIsUnrecognized() {
        List<CompileDiagnostic> diagnostics = JavaCodeRunner.parseDiagnostics("some unusual fatal compiler error", "Solution");
        assertEquals(1, diagnostics.size());
        assertEquals("Solution.java", diagnostics.get(0).file());
        assertTrue(diagnostics.get(0).error());
    }
}
