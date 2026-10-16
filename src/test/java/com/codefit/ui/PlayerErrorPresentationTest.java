package com.codefit.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PlayerErrorPresentationTest {
    @Test
    void busyIsActionableWithoutExposingSqlOrPrivateDiagnostics() {
        var message =
                PlayerErrorPresentation.message(
                        new IllegalStateException(
                                "private source", new SQLException("private SQL", null, 5)),
                        false,
                        false);
        assertTrue(message.contains("database is busy"));
        assertFalse(message.contains("private"));
    }

    @Test
    void skippedDiagnosticExplainsResumeRatherThanBlamingTheLearnerOrRunner() {
        var message =
                PlayerErrorPresentation.message(
                        new IllegalStateException(
                                "Diagnostic paused; resume before a new submission"),
                        false,
                        false);
        assertTrue(message.contains("Resume the baseline and task"));
        assertTrue(message.contains("draft is retained"));
    }

    @Test
    void derivationErrorsPreserveResultAndUnknownErrorsNeverEchoDetails() {
        assertTrue(
                PlayerErrorPresentation.message(new RuntimeException("hidden answer"), true, false)
                        .startsWith("Result saved — recommendation pending"));
        assertFalse(
                PlayerErrorPresentation.message(new RuntimeException("hidden answer"), false, false)
                        .contains("hidden answer"));
    }

    @Test
    void unavailableHistoricalInterpreterRetainsTheSavedRecord() {
        var message =
                PlayerErrorPresentation.message(
                        new RuntimeException("policy version"), false, true);
        assertTrue(message.contains("saved record is retained"));
        assertTrue(message.contains("compatible policy"));
    }
}
