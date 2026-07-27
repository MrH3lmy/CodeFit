package com.codefit.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * The result of one {@link CodeRunner#compile} call (#163): whether it succeeded, the parsed
 * {@link CompileDiagnostic}s (and the raw compiler text they came from, for a "copy full compiler
 * output" affordance), and the temporary work directory the compiled classes live in.
 *
 * <p>{@link AutoCloseable}: closing deletes the work directory (source file, {@code .class} files,
 * everything) whether compilation succeeded or failed, and is safe to call more than once. Callers
 * are expected to use try-with-resources so a compile failure — or an exception while running against
 * a successful compile — can never leave temporary files behind.
 */
public final class CompileOutcome implements AutoCloseable {

    private final boolean success;
    private final List<CompileDiagnostic> diagnostics;
    private final String rawOutput;
    private final Path workDir;
    private final String mainClassName;
    private boolean closed;

    CompileOutcome(boolean success, List<CompileDiagnostic> diagnostics, String rawOutput, Path workDir, String mainClassName) {
        this.success = success;
        this.diagnostics = List.copyOf(diagnostics);
        this.rawOutput = rawOutput;
        this.workDir = workDir;
        this.mainClassName = mainClassName;
    }

    public boolean success() {
        return success;
    }

    public List<CompileDiagnostic> diagnostics() {
        return diagnostics;
    }

    public String rawOutput() {
        return rawOutput;
    }

    Path workDir() {
        return workDir;
    }

    String mainClassName() {
        return mainClassName;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (workDir == null) {
            return;
        }
        try (var paths = Files.walk(workDir)) {
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
}
