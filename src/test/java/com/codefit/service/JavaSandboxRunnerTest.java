package com.codefit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSandboxRunnerTest {

    private final JavaSandboxRunner runner = new JavaSandboxRunner();

    @Test
    void correctSnippetProducesExpectedStdout() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        System.out.println("Hello, sandbox!");
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("Hello, sandbox!", null));

        assertEquals(JavaSandboxRunner.Outcome.CORRECT, result.outcome());
        assertEquals("Hello, sandbox!", result.stdout().strip());
        assertNoLeftoverSandboxDirectories();
    }

    @Test
    void snippetWithWrongOutputIsReportedDistinctly() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        System.out.println("wrong answer");
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("right answer", null));

        assertEquals(JavaSandboxRunner.Outcome.WRONG_OUTPUT, result.outcome());
    }

    @Test
    void snippetThatDoesNotCompileCapturesDiagnosticsSeparately() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        this is not valid java at all
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("", null));

        assertEquals(JavaSandboxRunner.Outcome.COMPILE_ERROR, result.outcome());
        assertFalse(result.compileDiagnostics().isBlank());
        assertNoLeftoverSandboxDirectories();
    }

    @Test
    void snippetThatThrowsExpectedExceptionIsGradedCorrect() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        int result = 1 / Integer.parseInt("0");
                        System.out.println(result);
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation(null, "ArithmeticException"));

        assertEquals(JavaSandboxRunner.Outcome.CORRECT, result.outcome());
    }

    @Test
    void snippetThatThrowsAnUnexpectedExceptionIsDistinguishedFromWrongOutput() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        String value = null;
                        System.out.println(value.length());
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("4", null));

        assertEquals(JavaSandboxRunner.Outcome.UNEXPECTED_EXCEPTION, result.outcome());
    }

    @Test
    void snippetThatShouldThrowButCompletesNormallyIsReportedAsMissingException() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        System.out.println("no exception here");
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation(null, "ArithmeticException"));

        assertEquals(JavaSandboxRunner.Outcome.MISSING_EXPECTED_EXCEPTION, result.outcome());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void infiniteLoopIsKilledByTheHardTimeout() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        while (true) {
                            // spin forever
                        }
                    }
                }
                """;
        JavaSandboxRunner.Limits shortTimeout = new JavaSandboxRunner.Limits(1, 64, 8 * 1024);

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("", null), shortTimeout);

        assertEquals(JavaSandboxRunner.Outcome.TIMEOUT, result.outcome());
        assertNoLeftoverSandboxDirectories();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void excessiveStdoutIsTruncatedRatherThanExhaustingTheHarness() {
        String source = """
                public class Solution {
                    public static void main(String[] args) {
                        String line = "0123456789012345678901234567890123456789012345678901234567890123456789";
                        for (int i = 0; i < 20_000; i++) {
                            System.out.println(line);
                        }
                    }
                }
                """;
        JavaSandboxRunner.Limits smallOutputCap = new JavaSandboxRunner.Limits(5, 64, 2048);

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("", null), smallOutputCap);

        assertTrue(result.outputTruncated());
        assertTrue(result.stdout().length() <= 2048 + 8192,
                "captured stdout should stay bounded near the configured cap, not grow to the full ~1.5MB of output");
    }

    @Test
    void unsafeConstructIsRejectedBeforeAnyProcessIsSpawned() {
        String source = """
                public class Solution {
                    public static void main(String[] args) throws Exception {
                        new ProcessBuilder("ls").start();
                    }
                }
                """;

        JavaSandboxRunner.ExecutionResult result = runner.run(source, "Solution",
                new JavaSandboxRunner.Expectation("", null));

        assertEquals(JavaSandboxRunner.Outcome.REJECTED_UNSAFE_SNIPPET, result.outcome());
        assertNoLeftoverSandboxDirectories();
    }

    @Test
    void sandboxDisablesGracefullyWhenNoJdkIsDiscoverable() {
        JavaSandboxRunner unavailable = new JavaSandboxRunner("/nonexistent/bogus/java/home/for-testing");

        assertFalse(unavailable.isAvailable());
        assertNotNull(unavailable.getUnavailabilityReason());

        JavaSandboxRunner.ExecutionResult result = unavailable.run(
                "public class Solution { public static void main(String[] a) {} }", "Solution",
                new JavaSandboxRunner.Expectation("", null));

        assertEquals(JavaSandboxRunner.Outcome.SANDBOX_UNAVAILABLE, result.outcome());
    }

    private void assertNoLeftoverSandboxDirectories() {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp, "codefit-java-sandbox-*")) {
            assertFalse(stream.iterator().hasNext(), "sandbox temp directory should be cleaned up after every attempt");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
