package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the guided curriculum practice loop's (#161) new pieces directly: the daily-target
 * preference (a plain {@code user_progress} column, same pattern as every other CodeFit preference)
 * and the DB-free "solved today" counting logic. {@link GuidedPracticeService#buildTodayPlan()}
 * itself composes {@link ProblemDashboardService}/{@link ProblemLibraryService} over the whole shared
 * roadmap, which is already covered by those services' own tests plus
 * {@code RealJuniorTrainingSheetImportTest}'s end-to-end import; asserting an exact
 * {@code mandatoryTotal}/{@code currentStage} here would just be re-testing them against
 * ever-accumulating shared test data.
 */
class GuidedPracticeServiceTest {

    private final GuidedPracticeService guidedPracticeService = new GuidedPracticeService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void dailyTargetProblemsRoundTripsThroughThePreferenceStore() {
        guidedPracticeService.setDailyTargetProblems(7);
        assertEquals(7, guidedPracticeService.getDailyTargetProblems());

        guidedPracticeService.setDailyTargetProblems(2);
        assertEquals(2, guidedPracticeService.getDailyTargetProblems());
    }

    @Test
    void buildTodayPlanReflectsTheCurrentDailyTarget() {
        guidedPracticeService.setDailyTargetProblems(5);
        TodayPlan plan = guidedPracticeService.buildTodayPlan();
        assertEquals(5, plan.dailyTargetProblems());
        assertNotNull(plan.revisitQueue(), "the revisit queue is always a (possibly empty) list, never null");
    }

    @Test
    void countSolvedOnOnlyCountsProblemsCompletedOnTheGivenDate() {
        LocalDate today = LocalDate.of(2026, 3, 15);
        LocalDate yesterday = today.minusDays(1);

        ProblemProgress solvedToday1 = solvedAt(1, today.atTime(9, 0));
        ProblemProgress solvedToday2 = solvedAt(2, today.atTime(22, 30));
        ProblemProgress solvedYesterday = solvedAt(3, yesterday.atTime(12, 0));
        ProblemProgress notStarted = ProblemProgress.notStarted(4);
        ProblemProgress inProgressNoCompletion = new ProblemProgress(0, 5, ProblemState.IN_PROGRESS, null, null, null,
                null, null, null, null, null, null, null, false, false, false, false, null, null);

        int count = GuidedPracticeService.countSolvedOn(
                List.of(solvedToday1, solvedToday2, solvedYesterday, notStarted, inProgressNoCompletion), today);

        assertEquals(2, count);
    }

    @Test
    void countSolvedOnIsZeroWithNoMatchingRows() {
        assertEquals(0, GuidedPracticeService.countSolvedOn(List.of(), LocalDate.now()));
    }

    private ProblemProgress solvedAt(long problemId, LocalDateTime completedAt) {
        return new ProblemProgress(0, problemId, ProblemState.SOLVED, null, null, null, null, null, null,
                null, null, null, null, false, false, false, false, completedAt, null);
    }
}
