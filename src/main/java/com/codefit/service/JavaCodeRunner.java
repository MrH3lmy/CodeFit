package com.codefit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and runs a learner's own full Java solution for the problem-solving workspace (#163),
 * using the local JDK's {@code javac}/{@code java} directly (no shell involved — every process is
 * started via {@link ProcessBuilder} with an explicit argument list). Distinct from
 * {@link JavaSandboxRunner} (which grades a bounded fill-in-the-blank flashcard exercise against a
 * single expected stdout/exception): this runner compiles and runs whatever complete program the
 * learner wrote, with their own custom standard input, and is designed to compile once and execute
 * several times against different inputs (see {@link #compile}/{@link #run} being separate steps).
 *
 * <p><b>Not a security sandbox.</b> This gives process isolation (a fresh temp directory per
 * compile, a wiped child environment, a hard wall-clock timeout, a capped heap, and output truncation
 * past a byte limit) appropriate for a trusted local single-user app running the learner's own code
 * on their own machine — not containment against a hostile submission. A learner's program shares the
 * host's network stack and filesystem visibility (within OS permissions); nothing here claims
 * otherwise. The authoritative result for any problem is always the external judge the learner
 * ultimately submits to, never this local run.
 */
public final class JavaCodeRunner implements CodeRunner {

    private static final int COMPILE_TIMEOUT_SECONDS = 15;
    // ".+\.java" is greedy, so on a Windows path like "C:\...\Solution.java:5: error: ..." it still
    // anchors on the literal ".java" rather than stopping at the drive letter's own colon.
    private static final Pattern DIAGNOSTIC_LINE_PATTERN =
            Pattern.compile("^(?<file>.+\\.java):(?<line>\\d+):\\s*(?<severity>error|warning):\\s*(?<message>.*)$");

    private final Path javaExecutable;
    private final Path javacExecutable;
    private final String unavailabilityReason;

    public JavaCodeRunner() {
        this(System.getProperty("java.home"));
    }

    /** Package-visible so tests can point at a bogus {@code javaHome} to exercise the graceful-disable path. */
    JavaCodeRunner(String javaHome) {
        Path home = javaHome == null || javaHome.isBlank() ? null : Paths.get(javaHome);
        Path resolvedJava = resolveExecutable(home, "java");
        Path resolvedJavac = resolveExecutable(home, "javac");
        if (home == null) {
            this.unavailabilityReason = "java.home is not set; the Java runner is unavailable.";
        } else if (resolvedJava == null || resolvedJavac == null) {
            this.unavailabilityReason = "No JDK found under " + home + ". A JRE-only install cannot compile Java code — "
                    + "install a JDK (e.g. Temurin/OpenJDK 17+) and set JAVA_HOME, or point CodeFit at one, then restart.";
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

    @Override
    public boolean isAvailable() {
        return unavailabilityReason == null;
    }

    @Override
    public String getUnavailabilityReason() {
        return unavailabilityReason;
    }

    @Override
    public CompileOutcome compile(String source, String mainClassName) {
        if (!isAvailable()) {
            return new CompileOutcome(false,
                    List.of(new CompileDiagnostic(mainClassName + ".java", 1, null, true, unavailabilityReason)),
                    unavailabilityReason, null, mainClassName);
        }
        Path workDir;
        try {
            workDir = Files.createTempDirectory("codefit-java-runner-");
        } catch (IOException exception) {
            return new CompileOutcome(false,
                    List.of(new CompileDiagnostic(mainClassName + ".java", 1, null, true,
                            "Unable to create a work directory: " + exception.getMessage())),
                    exception.getMessage(), null, mainClassName);
        }
        try {
            Path sourceFile = workDir.resolve(mainClassName + ".java");
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            List<String> command = List.of(javacExecutable.toString(), "-d", workDir.toString(), sourceFile.toString());
            ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
            restrictEnvironment(builder);

            Process process = builder.start();
            process.getOutputStream().close();
            StreamCapture stdout = StreamCapture.start(process.getInputStream(), RunLimits.defaults().maxOutputBytes());
            StreamCapture stderr = StreamCapture.start(process.getErrorStream(), RunLimits.defaults().maxOutputBytes());
            boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                RunCancellationToken.killTree(process);
            }
            stdout.awaitCompletion();
            stderr.awaitCompletion();
            String rawOutput = (stderr.text() + stdout.text()).strip();

            if (!finished) {
                return new CompileOutcome(false,
                        List.of(new CompileDiagnostic(mainClassName + ".java", 1, null, true,
                                "Compilation timed out after " + COMPILE_TIMEOUT_SECONDS + "s.")),
                        rawOutput, workDir, mainClassName);
            }
            boolean success = process.exitValue() == 0;
            List<CompileDiagnostic> diagnostics = parseDiagnostics(rawOutput, mainClassName);
            return new CompileOutcome(success, diagnostics, rawOutput, workDir, mainClassName);
        } catch (IOException exception) {
            return new CompileOutcome(false,
                    List.of(new CompileDiagnostic(mainClassName + ".java", 1, null, true,
                            "Unable to compile: " + exception.getMessage())),
                    exception.getMessage(), workDir, mainClassName);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CompileOutcome(false,
                    List.of(new CompileDiagnostic(mainClassName + ".java", 1, null, true, "Compilation was interrupted.")),
                    "", workDir, mainClassName);
        }
    }

    /**
     * Parses javac's plain-text diagnostics into structured {@link CompileDiagnostic}s. Each
     * diagnostic starts with a {@code file:line: error|warning: message} line; javac usually follows
     * it with the offending source line and a whitespace-then-caret line pointing at the column,
     * which — when present — is used to fill in {@link CompileDiagnostic#column()}.
     */
    static List<CompileDiagnostic> parseDiagnostics(String rawOutput, String mainClassName) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return diagnostics;
        }
        String[] lines = rawOutput.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = DIAGNOSTIC_LINE_PATTERN.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            int lineNumber = Integer.parseInt(matcher.group("line"));
            boolean isError = "error".equals(matcher.group("severity"));
            String message = matcher.group("message");
            Integer column = null;
            if (i + 2 < lines.length) {
                String caretLine = lines[i + 2];
                int caretIndex = caretLine.indexOf('^');
                if (caretIndex >= 0 && caretLine.substring(0, caretIndex).isBlank()) {
                    column = caretIndex + 1;
                }
            }
            String fileName = Paths.get(matcher.group("file")).getFileName().toString();
            diagnostics.add(new CompileDiagnostic(fileName, lineNumber, column, isError, message));
        }
        if (diagnostics.isEmpty()) {
            // javac produced output (e.g. a fatal "class not found") that didn't match the normal
            // per-diagnostic format; surface it as one whole-file error rather than silently dropping it.
            diagnostics.add(new CompileDiagnostic(mainClassName + ".java", 1, null, true, rawOutput));
        }
        return diagnostics;
    }

    @Override
    public RunResult run(CompileOutcome compiled, String stdin, RunLimits limits, RunCancellationToken cancellationToken) {
        if (!compiled.success() || compiled.workDir() == null) {
            throw new IllegalArgumentException("Cannot run a program that failed to compile.");
        }
        RunCancellationToken token = cancellationToken != null ? cancellationToken : new RunCancellationToken();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + limits.memoryLimitMb() + "m");
        command.add("-cp");
        command.add(compiled.workDir().toString());
        command.add(compiled.mainClassName());

        ProcessBuilder builder = new ProcessBuilder(command).directory(compiled.workDir().toFile());
        restrictEnvironment(builder);

        long startNanos = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return new RunResult(false, false, null, "", "Unable to start the program: " + exception.getMessage(),
                    false, elapsedMillisSince(startNanos));
        }
        token.attach(process);

        writeStdinThenClose(process.getOutputStream(), stdin);
        StreamCapture stdout = StreamCapture.start(process.getInputStream(), limits.maxOutputBytes());
        StreamCapture stderr = StreamCapture.start(process.getErrorStream(), limits.maxOutputBytes());
        try {
            boolean finished = process.waitFor(limits.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                // Hard kill: an infinite loop or a program blocked reading more stdin than we gave it
                // won't respond to a polite signal, so destroyForcibly (plus descendants) is the only
                // reliable way to stop it within the timeout budget.
                RunCancellationToken.killTree(process);
                stdout.awaitCompletion();
                stderr.awaitCompletion();
                return new RunResult(true, false, null, stdout.text(), stderr.text(),
                        stdout.truncated() || stderr.truncated(), elapsedMillisSince(startNanos));
            }
            stdout.awaitCompletion();
            stderr.awaitCompletion();
            if (token.isCancelled()) {
                return new RunResult(false, true, process.exitValue(), stdout.text(), stderr.text(),
                        stdout.truncated() || stderr.truncated(), elapsedMillisSince(startNanos));
            }
            return new RunResult(false, false, process.exitValue(), stdout.text(), stderr.text(),
                    stdout.truncated() || stderr.truncated(), elapsedMillisSince(startNanos));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            RunCancellationToken.killTree(process);
            return new RunResult(false, true, null, stdout.text(), stderr.text(), false, elapsedMillisSince(startNanos));
        } finally {
            if (process.isAlive()) {
                RunCancellationToken.killTree(process);
            }
        }
    }

    private long elapsedMillisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private void writeStdinThenClose(OutputStream stdin, String input) {
        try (stdin) {
            if (input != null && !input.isEmpty()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // The child may have already exited (or never reads stdin) — nothing more to do.
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

    private static final class StreamCapture {
        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private volatile boolean truncated;

        private StreamCapture(InputStream in, int maxBytes) {
            this.maxBytes = maxBytes;
            this.thread = new Thread(() -> drain(in), "java-runner-stream-capture");
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
                // stopped, the child's stdout pipe would fill up and block it, which would surface as
                // a false timeout instead of the truncation this is meant to report.
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
