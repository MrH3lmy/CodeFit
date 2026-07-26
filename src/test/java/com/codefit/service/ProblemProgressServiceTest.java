package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.FinalCategory;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.SolvedWith;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@link ProblemProgressService} keeps exactly one {@link ProblemProgress} row per problem
 * (#142), touching the shared local database idempotently like {@code AssessmentIsolationTest}.
 */
class ProblemProgressServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemProgressService progressService = new ProblemProgressService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private long fixtureProblemId(String externalCode) {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", externalCode,
                "Progress Fixture " + externalCode, null, "General", null, List.of());
        return problem.getId();
    }

    @Test
    void getOrCreateStartsANotStartedRecordAndIsIdempotent() {
        long problemId = fixtureProblemId("TF-142-PROGRESS-1");

        ProblemProgress first = progressService.getOrCreate(problemId);
        ProblemProgress second = progressService.getOrCreate(problemId);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void updatingProgressNeverCreatesASecondRowForTheSameProblem() {
        long problemId = fixtureProblemId("TF-142-PROGRESS-2");

        progressService.updateProgress(problemId, ProblemState.IN_PROGRESS, DifficultyLevel.MEDIUM,
                null, null, "first approach", null, null);
        ProblemProgress updated = progressService.updateProgress(problemId, ProblemState.SOLVED, DifficultyLevel.HARD,
                SolvedWith.HINT, FinalCategory.SHAKY, "final approach", "off-by-one on the boundary",
                LocalDateTime.now());

        assertEquals(ProblemState.SOLVED, updated.getState());
        assertEquals(DifficultyLevel.HARD, updated.getPerceivedDifficulty());
        assertEquals(SolvedWith.HINT, updated.getSolvedWith());
        assertEquals(FinalCategory.SHAKY, updated.getFinalCategory());
        assertEquals("final approach", updated.getApproachNotes());
        assertEquals("off-by-one on the boundary", updated.getMistakeNotes());
        assertNotNull(updated.getCompletedAt());

        // Only one row exists for this problem: getOrCreate must return that same updated row, not a fresh blank one.
        assertEquals(ProblemState.SOLVED, progressService.getOrCreate(problemId).getState());
    }
}
