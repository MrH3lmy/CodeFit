package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandValidatorTest {

    @Test
    void identicalCommandsMatch() {
        assertTrue(CommandValidator.matches("git commit -m \"fix bug\"", "git commit -m \"fix bug\""));
    }

    @Test
    void groupedShortFlagsMatchRegardlessOfOrder() {
        assertTrue(CommandValidator.matches("ls -la", "ls -al"));
        assertTrue(CommandValidator.matches("ls -al /tmp", "ls -la /tmp"));
    }

    @Test
    void longFlagOrderIsIrrelevant() {
        assertTrue(CommandValidator.matches("git commit -a -m \"fix bug\"", "git commit -m \"fix bug\" -a"));
    }

    @Test
    void flagEqualsValueFormIsHandled() {
        assertTrue(CommandValidator.matches("java -jar app.jar --spring.profiles.active=prod",
                "java -jar app.jar --spring.profiles.active = prod"));
    }

    @Test
    void quotedArgumentsAreComparedAsOneToken() {
        assertTrue(CommandValidator.matches("git commit -m 'fix the null check'",
                "git commit -m \"fix the null check\""));
        assertFalse(CommandValidator.matches("git commit -m 'wrong message'",
                "git commit -m \"fix the null check\""));
    }

    @Test
    void differentExecutableNeverMatches() {
        CommandValidator.Comparison comparison = CommandValidator.compare("dir -la", "ls -la");
        assertFalse(comparison.matches());
        assertFalse(comparison.executableMatches());
    }

    @Test
    void differentGitSubcommandNeverMatchesEvenWhenFlagsLineUp() {
        // Same executable and flag, completely different operation — must never be reported as
        // accepted just because "git" and "-m" both appear.
        CommandValidator.Comparison comparison = CommandValidator.compare("git checkout -m", "git commit -m \"x\"");
        assertFalse(comparison.matches());
        assertFalse(comparison.subcommandMatches());
    }

    @Test
    void missingFlagsAreReportedPrecisely() {
        CommandValidator.Comparison comparison = CommandValidator.compare("git commit", "git commit -m \"x\" -a");
        assertFalse(comparison.matches());
        assertTrue(comparison.executableMatches());
        assertTrue(comparison.subcommandMatches());
        assertEquals(2, comparison.missingFlags().size());
        assertTrue(comparison.extraFlags().isEmpty());
    }

    @Test
    void extraFlagIsReportedPrecisely() {
        CommandValidator.Comparison comparison = CommandValidator.compare("git commit -m \"x\" -a", "git commit -m \"x\"");
        assertFalse(comparison.matches());
        assertTrue(comparison.missingFlags().isEmpty());
        assertEquals(List.of("-a"), comparison.extraFlags());
    }

    @Test
    void incorrectFlagValueIsReportedPrecisely() {
        CommandValidator.Comparison comparison = CommandValidator.compare(
                "java -jar app.jar --spring.profiles.active=dev",
                "java -jar app.jar --spring.profiles.active=prod");
        assertFalse(comparison.matches());
        assertEquals(List.of("--spring.profiles.active"), comparison.incorrectFlagValues());
    }

    @Test
    void positionalArgumentOrderIsSignificant() {
        assertTrue(CommandValidator.matches("cp a.txt b.txt", "cp a.txt b.txt"));
        assertFalse(CommandValidator.matches("cp b.txt a.txt", "cp a.txt b.txt"));
    }

    @Test
    void linuxRemoveForceRecursiveAcceptsEquivalentFlagOrder() {
        assertTrue(CommandValidator.matches("rm -rf build", "rm -fr build"));
    }

    @Test
    void gitPushWithUpstreamFlagOrderIsIrrelevant() {
        assertTrue(CommandValidator.matches("git push -u origin main", "git push origin main -u"));
    }
}
