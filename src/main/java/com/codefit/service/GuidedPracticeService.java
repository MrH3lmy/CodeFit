package com.codefit.service;

import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.UserProgress;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Builds the guided curriculum practice loop's {@link TodayPlan} (#161) and owns the one new piece of
 * learner preference it introduces: the daily target, stored on {@code user_progress} the same way
 * every other CodeFit preference is (see {@code FocusPreferenceService}, {@code GuidedTrainingService}).
 *
 * <p>Deliberately a thin composition layer: every figure in {@link TodayPlan} either comes straight
 * from {@link ProblemDashboardService}/{@link ProblemLibraryService}, or is one new read
 * ({@link #countSolvedOn}) over {@link com.codefit.model.ProblemProgress} — nothing here duplicates
 * their aggregation logic, so the Today screen can never show a different frontier/recommendation
 * than the Problem Library or Dashboard screens do.
 */
public class GuidedPracticeService {

    private final ProblemLibraryService libraryService;
    private final ProblemDashboardService dashboardService;
    private final ProblemProgressRepository progressRepository;
    private final UserProgressRepository userProgressRepository;

    public GuidedPracticeService() {
        this(new ProblemLibraryService(), new ProblemDashboardService(), new ProblemProgressRepository(), new UserProgressRepository());
    }

    public GuidedPracticeService(ProblemLibraryService libraryService, ProblemDashboardService dashboardService,
                                 ProblemProgressRepository progressRepository, UserProgressRepository userProgressRepository) {
        this.libraryService = libraryService;
        this.dashboardService = dashboardService;
        this.progressRepository = progressRepository;
        this.userProgressRepository = userProgressRepository;
    }

    public TodayPlan buildTodayPlan() {
        return buildTodayPlan(LocalDate.now());
    }

    /** Package-visible so tests can pin "today" instead of depending on the clock. */
    TodayPlan buildTodayPlan(LocalDate today) {
        ProblemDashboard dashboard = dashboardService.build();
        ProblemDashboard.CoreProgress coreProgress = dashboard.coreProgress();
        Optional<ProblemLibraryEntry> nextRecommended = libraryService.getNextRecommendedProblem();
        String reason = nextRecommended.map(ProblemDashboardService::describeRecommendation)
                .orElse("Every roadmap problem is already solved — nothing left to recommend.");

        return new TodayPlan(coreProgress.currentStage(), coreProgress.currentSet(), coreProgress.mandatoryTotal(),
                coreProgress.mandatoryCompleted(), getDailyTargetProblems(), countSolvedOn(progressRepository.findAll(), today),
                nextRecommended, reason, libraryService.getRevisitQueue(), dashboard.timingInsights().bottleneckPhase());
    }

    public int getDailyTargetProblems() {
        return userProgressRepository.getProgress().getDailyTargetProblems();
    }

    public void setDailyTargetProblems(int target) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setDailyTargetProblems(target);
        userProgressRepository.save(progress);
    }

    /** Package-visible, DB-free counting logic, unit tested directly against a hand-built list rather
     *  than the shared test database (whose {@code ProblemProgress} rows accumulate across every test
     *  that has ever run, making an exact "solved today" count impossible to assert reliably there). */
    static int countSolvedOn(List<ProblemProgress> progressRows, LocalDate date) {
        return (int) progressRows.stream()
                .filter(progress -> progress.getState() == ProblemState.SOLVED
                        && progress.getCompletedAt() != null
                        && progress.getCompletedAt().toLocalDate().equals(date))
                .count();
    }
}
