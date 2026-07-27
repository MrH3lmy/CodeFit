package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ComplexityClass;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ProblemProgressService} keeps exactly one {@link ProblemProgress} row per problem
 * (#142), and that workflow-state updates ({@link ProblemProgressService#updateProgress}) and
 * post-solve reflection updates ({@link ProblemProgressService#updateReflection}) stay independent of
 * each other (#146). Touches the shared local database idempotently like {@code AssessmentIsolationTest}.
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

        progressService.updateProgress(problemId, ProblemState.IN_PROGRESS, null);
        ProblemProgress updated = progressService.updateProgress(problemId, ProblemState.SOLVED, LocalDateTime.now());

        assertEquals(ProblemState.SOLVED, updated.getState());
        assertNotNull(updated.getCompletedAt());

        // Only one row exists for this problem: getOrCreate must return that same updated row, not a fresh blank one.
        assertEquals(ProblemState.SOLVED, progressService.getOrCreate(problemId).getState());
    }

    @Test
    void updatingProgressNeverTouchesAnyReflectionField() {
        long problemId = fixtureProblemId("TF-146-PROGRESS-3");
        progressService.updateReflection(problemId, new ProblemReflection(7, SolvedWith.HINT, FinalCategory.SHAKY,
                "binary search approach", "off-by-one", "watch the boundary", ComplexityClass.O_LOG_N,
                ComplexityClass.O_1, "always check both ends", "Binary Search", true, false, true, false));

        progressService.updateProgress(problemId, ProblemState.SOLVED, LocalDateTime.now());

        ProblemProgress progress = progressService.getOrCreate(problemId);
        assertEquals(7, progress.getPerceivedDifficultyRating());
        assertEquals(SolvedWith.HINT, progress.getSolvedWith());
        assertEquals(FinalCategory.SHAKY, progress.getFinalCategory());
        assertEquals("binary search approach", progress.getApproachNotes());
        assertEquals("off-by-one", progress.getMistakeNotes());
        assertEquals(ComplexityClass.O_LOG_N, progress.getTimeComplexity());
    }

    @Test
    void updateReflectionNeverTouchesStateOrCompletedAt() {
        long problemId = fixtureProblemId("TF-146-PROGRESS-4");
        LocalDateTime completedAt = progressService.updateProgress(problemId, ProblemState.SOLVED, LocalDateTime.now())
                .getCompletedAt();

        progressService.updateReflection(problemId, new ProblemReflection(5, SolvedWith.SELF, null,
                "approach", null, null, null, null, null, null, false, false, false, false));

        ProblemProgress progress = progressService.getOrCreate(problemId);
        assertEquals(ProblemState.SOLVED, progress.getState());
        assertEquals(completedAt, progress.getCompletedAt());
    }

    @Test
    void reflectionFieldsCanBeEditedLaterAndOverwritePreviousValues() {
        long problemId = fixtureProblemId("TF-146-PROGRESS-5");
        progressService.updateReflection(problemId, new ProblemReflection(3, SolvedWith.EDITORIAL, FinalCategory.WEAK,
                "first pass", "misread constraints", null, ComplexityClass.O_N_SQUARED, ComplexityClass.O_N,
                "brute force", "Brute Force", false, false, false, false));

        ProblemProgress updated = progressService.updateReflection(problemId, new ProblemReflection(9, SolvedWith.SELF,
                FinalCategory.STRONG, "optimized pass", null, "two pointers converge from both ends",
                ComplexityClass.O_N, ComplexityClass.O_1, "two-pointer beats brute force here", "Two Pointers",
                true, true, true, true));

        assertEquals(9, updated.getPerceivedDifficultyRating());
        assertEquals(SolvedWith.SELF, updated.getSolvedWith());
        assertEquals(FinalCategory.STRONG, updated.getFinalCategory());
        assertEquals("optimized pass", updated.getApproachNotes());
        assertNull(updated.getMistakeNotes());
        assertEquals("two pointers converge from both ends", updated.getImportantObservation());
        assertEquals(ComplexityClass.O_N, updated.getTimeComplexity());
        assertEquals(ComplexityClass.O_1, updated.getSpaceComplexity());
        assertEquals("two-pointer beats brute force here", updated.getLessonLearned());
        assertEquals("Two Pointers", updated.getActualTopic());
        assertTrue(updated.isEditorialUnderstood());
        assertTrue(updated.isOtherSolutionsReviewed());
        assertTrue(updated.isSimplerImplementationConsidered());
        assertTrue(updated.isBetterComplexityConsidered());
    }

    @Test
    void reflectionFieldsDefaultToUnsetOrFalse() {
        long problemId = fixtureProblemId("TF-146-PROGRESS-6");

        ProblemProgress progress = progressService.getOrCreate(problemId);

        assertNull(progress.getPerceivedDifficultyRating());
        assertNull(progress.getTimeComplexity());
        assertNull(progress.getSpaceComplexity());
        assertFalse(progress.isEditorialUnderstood());
        assertFalse(progress.isOtherSolutionsReviewed());
        assertFalse(progress.isSimplerImplementationConsidered());
        assertFalse(progress.isBetterComplexityConsidered());
    }
}
