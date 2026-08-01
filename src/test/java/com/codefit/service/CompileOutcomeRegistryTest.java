package com.codefit.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #163 fix for a real leak: a {@link CompileOutcome} the learner never recompiled after
 * (navigated away, or quit the app) previously kept its temp work directory forever. Both paths route
 * through {@link CompileOutcomeRegistry}, so this exercises the registry directly against real
 * filesystem temp directories rather than the full controller/application lifecycle.
 */
class CompileOutcomeRegistryTest {

    @AfterEach
    void clearRegistry() {
        CompileOutcomeRegistry.closeCurrent();
    }

    @Test
    void registeringANewOutcomeClosesAndDeletesThePreviousOnesWorkDir() throws IOException {
        Path firstDir = Files.createTempDirectory("compile-outcome-registry-test-1");
        Path secondDir = Files.createTempDirectory("compile-outcome-registry-test-2");
        CompileOutcome first = new CompileOutcome(true, List.of(), "", firstDir, "Solution");
        CompileOutcome second = new CompileOutcome(true, List.of(), "", secondDir, "Solution");

        CompileOutcomeRegistry.replace(first);
        assertTrue(Files.exists(firstDir), "the first outcome's work dir must still exist while it's the live one");

        CompileOutcomeRegistry.replace(second);

        assertFalse(Files.exists(firstDir), "replacing the live outcome must close (and delete the temp dir of) the previous one");
        assertTrue(Files.exists(secondDir));
    }

    @Test
    void closeCurrentDeletesWhateverIsLeftRegistered() throws IOException {
        Path workDir = Files.createTempDirectory("compile-outcome-registry-test-close");
        CompileOutcomeRegistry.replace(new CompileOutcome(true, List.of(), "", workDir, "Solution"));

        CompileOutcomeRegistry.closeCurrent();

        assertFalse(Files.exists(workDir), "#163: an outcome nobody ever recompiled must not leak its temp directory");
    }

    @Test
    void closeCurrentWithNothingRegisteredIsAHarmlessNoOp() {
        CompileOutcomeRegistry.closeCurrent();
        CompileOutcomeRegistry.closeCurrent();
    }
}
