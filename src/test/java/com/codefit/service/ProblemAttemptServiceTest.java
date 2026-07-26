package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.SubmissionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a {@link com.codefit.model.Problem} can accumulate many {@link ProblemAttempt} rows with
 * a gapless, unique attempt-number sequence (#142), touching the shared local database idempotently.
 */
class ProblemAttemptServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void recordingSeveralAttemptsAssignsAGaplessIncreasingAttemptNumber() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-ATTEMPT-1",
                "Attempt Fixture", null, "General", null, List.of());
        int attemptsBefore = attemptService.getAttempts(problem.getId()).size();

        ProblemAttempt firstNewAttempt = attemptService.recordAttempt(problem.getId(), SubmissionResult.WA,
                120, 300, 600, 60, "misread the constraints");
        ProblemAttempt secondNewAttempt = attemptService.recordAttempt(problem.getId(), SubmissionResult.AC,
                60, 90, 400, 30, "fixed the boundary check");

        assertEquals(attemptsBefore + 1, firstNewAttempt.attemptNumber());
        assertEquals(attemptsBefore + 2, secondNewAttempt.attemptNumber());
        assertEquals(SubmissionResult.WA, firstNewAttempt.submissionResult());
        assertEquals(SubmissionResult.AC, secondNewAttempt.submissionResult());

        List<ProblemAttempt> attempts = attemptService.getAttempts(problem.getId());
        assertEquals(attemptsBefore + 2, attempts.size());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.problemId() == problem.getId()));
    }

    @Test
    void aSingleProblemsProgressRecordIsNotAffectedByHowManyAttemptsItHas() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-ATTEMPT-2",
                "Independent Attempt Fixture", null, "General", null, List.of());

        attemptService.recordAttempt(problem.getId(), SubmissionResult.WA, null, null, null, null, null);
        attemptService.recordAttempt(problem.getId(), SubmissionResult.TLE, null, null, null, null, null);
        attemptService.recordAttempt(problem.getId(), SubmissionResult.AC, null, null, null, null, null);

        ProblemProgressService progressService = new ProblemProgressService();
        // getOrCreate must still resolve to exactly one progress row, independent of how many attempts exist.
        assertEquals(progressService.getOrCreate(problem.getId()).getId(),
                progressService.getOrCreate(problem.getId()).getId());
    }
}
