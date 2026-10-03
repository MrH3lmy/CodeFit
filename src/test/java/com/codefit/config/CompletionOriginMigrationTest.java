package com.codefit.config;

import com.codefit.model.CompletionOrigin;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * #183 review fix, round 4: an install that already had {@code problem_attempts} rows before
 * {@code completion_origin} existed - including rows whose {@code submission_result} is {@code ACX}
 * - must have those rows become {@code UNKNOWN} when the column is added, never silently
 * reclassified as {@code FRESH_ATTEMPT} just because {@code ACX} looks like a successful completion.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class CompletionOriginMigrationTest {

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void existingAcxRowsBecomeUnknownNeverGuessedAsFreshAttemptWhenTheColumnIsAdded() throws SQLException {
        long problemId = new ProblemRepository().save(
                new com.codefit.model.Problem("MIGRATION-FIXTURE", "JUNIOR", "Migration Fixture",
                        null, "General", null, null)).getId();

        long legacyAttemptId;
        try (Connection connection = DatabaseConfig.getConnection(); Statement statement = connection.createStatement()) {
            // Simulate an install that predates this column entirely: drop it, then insert a row the
            // way the pre-round-4 app always did - no opinion on completion origin at all, including
            // the exact ACX shape markPreviouslySolved() produces.
            statement.execute("ALTER TABLE problem_attempts DROP COLUMN completion_origin");

            try (PreparedStatement insertAttempt = connection.prepareStatement(
                    "INSERT INTO problem_attempts (problem_id, attempt_number, submission_result, submitted_at) "
                            + "VALUES (?, 1, 'ACX', '2025-01-01 10:00:00')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertAttempt.setLong(1, problemId);
                insertAttempt.executeUpdate();
                try (ResultSet keys = insertAttempt.getGeneratedKeys()) {
                    keys.next();
                    legacyAttemptId = keys.getLong(1);
                }
            }
        }

        // The exact idempotent startup path every real app launch (including an upgrade) runs.
        DatabaseConfig.initialize();

        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT submission_result, completion_origin FROM problem_attempts WHERE id = ?")) {
            statement.setLong(1, legacyAttemptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertEquals(true, resultSet.next());
                assertEquals("ACX", resultSet.getString("submission_result"), "the pre-existing value is untouched");
                assertEquals(CompletionOrigin.UNKNOWN.name(), resultSet.getString("completion_origin"),
                        "a row that existed before this column did must become UNKNOWN, never reinterpreted as "
                                + "FRESH_ATTEMPT just because its submission_result happens to be ACX");
                assertNotEquals(CompletionOrigin.FRESH_ATTEMPT.name(), resultSet.getString("completion_origin"));
            }
        }

        // And the repository layer reads that same legacy row back as UNKNOWN, not as a default null.
        ProblemAttempt reloaded = new ProblemAttemptRepository().findByProblemId(problemId).get(0);
        assertEquals(CompletionOrigin.UNKNOWN, reloaded.completionOrigin());
    }

    @Test
    void aGenuinelyNewWorkspaceAttemptIsRecordedAsFreshAttempt() throws SQLException {
        long problemId = new ProblemRepository().save(
                new com.codefit.model.Problem("MIGRATION-FIXTURE-2", "JUNIOR", "Migration Fixture 2",
                        null, "General", null, null)).getId();

        com.codefit.service.ProblemAttemptService attemptService = new com.codefit.service.ProblemAttemptService();
        ProblemAttempt saved = attemptService.recordAttempt(problemId, SubmissionResult.AC, null, null, null, null, null,
                null, CompletionOrigin.FRESH_ATTEMPT);

        assertEquals(CompletionOrigin.FRESH_ATTEMPT, saved.completionOrigin());

        ProblemAttempt reloaded = new ProblemAttemptRepository().findByProblemId(problemId).get(0);
        assertEquals(CompletionOrigin.FRESH_ATTEMPT, reloaded.completionOrigin());
        assertEquals(LocalDateTime.now().getYear(), reloaded.submittedAt().getYear());
    }
}
