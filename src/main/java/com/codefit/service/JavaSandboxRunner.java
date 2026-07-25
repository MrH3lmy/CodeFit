package com.codefit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and runs a learner's Java attempt in a throwaway child JVM, never inside the JavaFX
 * application process. Every attempt gets its own temporary directory, a hard wall-clock timeout,
 * a capped heap, a restricted classpath and environment, and is cleaned up whether it succeeds,
 * fails, or throws.
 *
 * <p><b>Security assumptions and limitations</b> (also documented in the PR that introduced this
 * class): the child JVM shares the host's network stack. Modern JDKs removed the {@code
 * SecurityManager} API this project could otherwise have used to deny socket creation
 * (deprecated for removal since JEP 411 in Java 17, thrown by default since Java 24), so there is
 * no in-process way left to block network I/O from Java code once it runs. "No network access" here
 * means: process isolation, a restricted environment (no inherited proxy/credential variables), a
 * hard timeout, and {@link JavaSnippetGuard} rejecting sources that obviously reach for
 * {@code java.net}/{@code javax.net} — not an enforced network deny. A learner attempt that works
 * around the textual guard (string-built class names, reflection, etc.) could still open a
 * connection within the timeout window. This is acceptable only because the scope is trusted,
 * app-authored templates with a bounded learner-supplied blank, run by the repo owner for their own
 * study — not arbitrary untrusted code from third parties.
 */
public final class JavaSandboxRunner {

    private static final int COMPILE_TIMEOUT_SECONDS = 10;
    private static final Pattern UNCAUGHT_EXCEPTION_PATTERN =
            Pattern.compile("Exception in thread \"[^\"]*\" ([\\w.$]+)");

    private final Path javaExecutable;
    private final Path javacExecutable;
    private final String unavailabilityReason;

    public JavaSandboxRunner() {
        this(System.getProperty("java.home"));
    }

    /** Package-visible so tests can point at a bogus {@code javaHome} to exercise the graceful-disable path. */
    JavaSandboxRunner(String javaHome) {
        Path home = javaHome == null || javaHome.isBlank() ? null : Paths.get(javaHome);
        Path resolvedJava = resolveExecutable(home, "java");
        Path resolvedJavac = resolveExecutable(home, "javac");
        if (home == null) {
            this.unavailabilityReason = "java.home is not set; the Java code sandbox is unavailable.";
        } else if (resolvedJava == null || resolvedJavac == null) {
            this.unavailabilityReason = "No JDK found under " + home
                    + " (java or javac is missing — a JRE-only install cannot compile snippets).";
        } else {
            this.unavailabilityReason = null;
        }
        this.javaExecutable = resolvedJava;
        this.javacExecutable = resolvedJavac;
    }

    private static Path resolveExecutable(Path javaHome, String name) {
        if (javaHome == null) {
            return null;
        }
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
        Path candidate = javaHome.resolve("bin").resolve(exeName);
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate) ? candidate : null;
    }

    public boolean isAvailable() {
        return unavailabilityReason == null;
    }

    public String getUnavailabilityReason() {
        return unavailabilityReason;
    }

    public ExecutionResult run(String javaSource, String mainClassName, Expectation expectation) {
        return run(javaSource, mainClassName, expectation, Limits.defaults());
    }

    public ExecutionResult run(String javaSource, String mainClassName, Expectation expectation, Limits limits) {
        if (!isAvailable()) {
            return ExecutionResult.unavailable(unavailabilityReason);
        }
        String violation = JavaSnippetGuard.violation(javaSource);
        if (violation != null) {
            return ExecutionResult.rejected(violation);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("codefit-java-sandbox-");
            Path sourceFile = tempDir.resolve(mainClassName + ".java");
            Files.writeString(sourceFile, javaSource, StandardCharsets.UTF_8);

            CompileOutcome compileOutcome = compile(tempDir, sourceFile);
            if (!compileOutcome.success()) {
                return ExecutionResult.compileError(compileOutcome.diagnostics());
            }

            RunOutcome runOutcome = execute(tempDir, mainClassName, limits);
            return grade(runOutcome, expectation);
        } catch (IOException exception) {
            return ExecutionResult.compileError("Unable to prepare the sandbox: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ExecutionResult.compileError("Sandbox execution was interrupted.");
        } finally {
            // Cleanup must happen on every path, including exceptions thrown above, so a failed
            // attempt never leaves compiled learner classes or source on disk.
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private CompileOutcome compile(Path tempDir, Path sourceFile) throws IOException, InterruptedException {
        List<String> command = List.of(javacExecutable.toString(), "-d", tempDir.toString(), sourceFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(tempDir.toFile());
        restrictEnvironment(builder);

        Process process = builder.start();
        try {
            StreamCapture stdout = StreamCapture.start(process.getInputStream(), Limits.defaults().maxOutputBytes());
            StreamCapture stderr = StreamCapture.start(process.getErrorStream(), Limits.defaults().maxOutputBytes());
            boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdout.awaitCompletion();
                stderr.awaitCompletion();
                return new CompileOutcome(false, "Compilation timed out after " + COMPILE_TIMEOUT_SECONDS + "s.");
            }
            stdout.awaitCompletion();
            stderr.awaitCompletion();
            String diagnostics = (stderr.text() + stdout.text()).strip();
            return new CompileOutcome(process.exitValue() == 0, diagnostics);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private RunOutcome execute(Path tempDir, String mainClassName, Limits limits) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + limits.memoryLimitMb() + "m");
        // The classpath is exactly the temp dir javac just wrote to — never the JavaFX
        // application's own classpath — so the attempt cannot reach application classes,
        // the SQLite driver, or anything else already loaded in the parent process.
        command.add("-cp");
        command.add(tempDir.toString());
        command.add(mainClassName);

        ProcessBuilder builder = new ProcessBuilder(command).directory(tempDir.toFile());
        restrictEnvironment(builder);

        Process process = builder.start();
        try {
            StreamCapture stdout = StreamCapture.start(process.getInputStream(), limits.maxOutputBytes());
            StreamCapture stderr = StreamCapture.start(process.getErrorStream(), limits.maxOutputBytes());
            boolean finished = process.waitFor(limits.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                // Hard kill: waitFor returning false means the process is still running past its
                // budget (e.g. an infinite loop) — destroyForcibly is the only reliable way to stop
                // a misbehaving child JVM, since it may not respond to a polite signal.
                process.destroyForcibly();
                stdout.awaitCompletion();
                stderr.awaitCompletion();
                return new RunOutcome(true, null, stdout.text(), stderr.text(), stdout.truncated() || stderr.truncated());
            }
            stdout.awaitCompletion();
            stderr.awaitCompletion();
            return new RunOutcome(false, process.exitValue(), stdout.text(), stderr.text(), stdout.truncated() || stderr.truncated());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void restrictEnvironment(ProcessBuilder builder) {
        // Wipe the parent's environment before handing anything to the child: the JavaFX process's
        // env may carry unrelated app configuration, and the child has no legitimate need for it.
        // PATH is the one exception, kept only because some platforms' dynamic linker needs it to
        // resolve the JVM's own native libraries.
        Map<String, String> env = builder.environment();
        String path = System.getenv("PATH");
        env.clear();
        if (path != null) {
            env.put("PATH", path);
        }
    }

    private ExecutionResult grade(RunOutcome outcome, Expectation expectation) {
        if (outcome.timedOut()) {
            return ExecutionResult.timeout(outcome.stdout(), outcome.stderr(), outcome.outputTruncated());
        }
        if (outcome.exitCode() == 0) {
            if (expectation.expectsException()) {
                return ExecutionResult.missingExpectedException(expectation.expectedExceptionSimpleName(),
                        outcome.stdout(), outcome.outputTruncated());
            }
            String expected = expectation.expectedOutput() == null ? "" : expectation.expectedOutput().strip();
            String actual = outcome.stdout().strip();
            return expected.equals(actual)
                    ? ExecutionResult.correct(outcome.stdout(), outcome.outputTruncated())
                    : ExecutionResult.wrongOutput(expected, outcome.stdout(), outcome.outputTruncated());
        }

        String thrown = extractThrownExceptionSimpleName(outcome.stderr());
        if (thrown != null && expectation.expectsException()
                && thrown.equalsIgnoreCase(simpleName(expectation.expectedExceptionSimpleName()))) {
            return ExecutionResult.correct(outcome.stdout(), outcome.outputTruncated());
        }
        return ExecutionResult.unexpectedException(thrown, outcome.stderr(), outcome.outputTruncated());
    }

    private static String extractThrownExceptionSimpleName(String stderr) {
        if (stderr == null) {
            return null;
        }
        Matcher matcher = UNCAUGHT_EXCEPTION_PATTERN.matcher(stderr);
        if (!matcher.find()) {
            return null;
        }
        return simpleName(matcher.group(1));
    }

    private static String simpleName(String possiblyQualified) {
        if (possiblyQualified == null) {
            return null;
        }
        int lastDot = possiblyQualified.lastIndexOf('.');
        return lastDot < 0 ? possiblyQualified : possiblyQualified.substring(lastDot + 1);
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover temp file is a nuisance, not a correctness issue.
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record CompileOutcome(boolean success, String diagnostics) {
    }

    private record RunOutcome(boolean timedOut, Integer exitCode, String stdout, String stderr, boolean outputTruncated) {
    }

    /** What the sandboxed run's stdout/exception is graded against; exactly one should be set. */
    public record Expectation(String expectedOutput, String expectedExceptionSimpleName) {
        public boolean expectsException() {
            return expectedExceptionSimpleName != null && !expectedExceptionSimpleName.isBlank();
        }
    }

    public record Limits(int timeoutSeconds, int memoryLimitMb, int maxOutputBytes) {
        private static final Limits DEFAULTS = new Limits(5, 128, 64 * 1024);

        public static Limits defaults() {
            return DEFAULTS;
        }
    }

    public enum Outcome {
        SANDBOX_UNAVAILABLE, REJECTED_UNSAFE_SNIPPET, COMPILE_ERROR, TIMEOUT,
        CORRECT, WRONG_OUTPUT, UNEXPECTED_EXCEPTION, MISSING_EXPECTED_EXCEPTION
    }

    public record ExecutionResult(Outcome outcome, String stdout, String stderr, String compileDiagnostics,
                                   boolean outputTruncated, String message) {

        static ExecutionResult unavailable(String reason) {
            return new ExecutionResult(Outcome.SANDBOX_UNAVAILABLE, "", "", "", false, reason);
        }

        static ExecutionResult rejected(String reason) {
            return new ExecutionResult(Outcome.REJECTED_UNSAFE_SNIPPET, "", "", "", false, reason);
        }

        static ExecutionResult compileError(String diagnostics) {
            return new ExecutionResult(Outcome.COMPILE_ERROR, "", "", diagnostics, false,
                    "Did not compile:\n" + diagnostics);
        }

        static ExecutionResult timeout(String stdout, String stderr, boolean truncated) {
            return new ExecutionResult(Outcome.TIMEOUT, stdout, stderr, "", truncated,
                    "Ran too long and was terminated (possible infinite loop).");
        }

        static ExecutionResult correct(String stdout, boolean truncated) {
            return new ExecutionResult(Outcome.CORRECT, stdout, "", "", truncated, "Correct.");
        }

        static ExecutionResult wrongOutput(String expected, String actual, boolean truncated) {
            return new ExecutionResult(Outcome.WRONG_OUTPUT, actual, "", "", truncated,
                    "Ran, but output didn't match.\nExpected:\n" + expected + "\nActual:\n" + actual);
        }

        static ExecutionResult unexpectedException(String thrownSimpleName, String stderr, boolean truncated) {
            return new ExecutionResult(Outcome.UNEXPECTED_EXCEPTION, "", stderr, "", truncated,
                    "Threw " + (thrownSimpleName == null ? "an exception" : thrownSimpleName) + " unexpectedly.");
        }

        static ExecutionResult missingExpectedException(String expectedExceptionSimpleName, String stdout, boolean truncated) {
            return new ExecutionResult(Outcome.MISSING_EXPECTED_EXCEPTION, stdout, "", "", truncated,
                    "Expected " + expectedExceptionSimpleName + " to be thrown, but the program completed normally.");
        }
    }

    private static final class StreamCapture {
        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private volatile boolean truncated;

        private StreamCapture(InputStream in, int maxBytes) {
            this.maxBytes = maxBytes;
            this.thread = new Thread(() -> drain(in), "java-sandbox-stream-capture");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static StreamCapture start(InputStream in, int maxBytes) {
            return new StreamCapture(in, maxBytes);
        }

        private void drain(InputStream in) {
            byte[] chunk = new byte[8192];
            try {
                int read;
                // Keep reading past the cap and discard the excess rather than stopping: if we
                // stopped, the child's stdout pipe would fill up and block it, which would surface
                // as a false timeout instead of the truncation this is meant to report.
                while ((read = in.read(chunk)) != -1) {
                    synchronized (buffer) {
                        int remaining = maxBytes - buffer.size();
                        if (remaining > 0) {
                            int toKeep = Math.min(read, remaining);
                            buffer.write(chunk, 0, toKeep);
                            if (toKeep < read) {
                                truncated = true;
                            }
                        } else {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closed because the process was destroyed; nothing left to capture.
            }
        }

        void awaitCompletion() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }

        boolean truncated() {
            return truncated;
        }
    }
}
