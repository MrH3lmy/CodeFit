package com.codefit.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

class JavaCodeRunnerCompilationTest {
    @Test
    void cancellationAfterCompilerProcessStartsIsTypedAndCleansArtifacts() {
        var process = new AtomicReference<ProcessHandle>();
        var tokenRef = new AtomicReference<RunCancellationToken>();
        var token =
                new RunCancellationToken(
                        child -> {
                            process.set(child);
                            tokenRef.get().cancel();
                        });
        tokenRef.set(token);
        java.nio.file.Path directory;
        try (var compiled =
                new JavaCodeRunner()
                        .compile(
                                "public class Main { public static void main(String[] args){} }",
                                "Main",
                                new RunLimits(15, 128, 4096),
                                token)) {
            assertNotNull(process.get());
            assertEquals(CompileOutcome.Status.CANCELLED, compiled.status());
            assertFalse(compiled.success());
            assertFalse(process.get().isAlive());
            directory = compiled.workDir();
            assertNotNull(directory);
        }
        assertFalse(Files.exists(directory));
    }

    @Test
    void unavailableJdkIsNotASyntaxError() {
        try (var compiled =
                new JavaCodeRunner("/codefit-test-nonexistent-jdk")
                        .compile(
                                "public class Main {}",
                                "Main",
                                RunLimits.defaults(),
                                new RunCancellationToken())) {
            assertEquals(CompileOutcome.Status.TOOL_UNAVAILABLE, compiled.status());
            assertFalse(compiled.success());
            assertNull(compiled.workDir());
            assertFalse(compiled.rawOutput().isBlank());
        }
    }

    @Test
    void mainClassCannotEscapeTemporaryDirectory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaCodeRunner().compile("class Main {}", "../Main"));
    }

    @Test
    void processRegistrationCannotExtendTheCompileDeadline() {
        var token =
                new RunCancellationToken(
                        child -> {
                            try {
                                Thread.sleep(1100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        });
        try (var compiled =
                new JavaCodeRunner()
                        .compile(
                                "public class Main {}",
                                "Main",
                                new RunLimits(1, 128, 4096),
                                token)) {
            assertEquals(CompileOutcome.Status.TIMED_OUT, compiled.status());
            assertFalse(compiled.success());
            assertTrue(compiled.rawOutput().contains("timed out"));
        }
    }

    @Test
    void validUnicodeClassNamesRetainExistingWorkspaceSupport() {
        var runner = new JavaCodeRunner();
        try (var compiled =
                runner.compile(
                        "public class Café { public static void main(String[] args) {"
                            + " System.out.print(\"OK\"); } }",
                        "Café")) {
            assertTrue(compiled.success(), compiled::rawOutput);
            assertEquals(
                    "OK",
                    runner.run(compiled, "", RunLimits.defaults(), new RunCancellationToken())
                            .stdout());
        }
    }
}
