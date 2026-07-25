package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.repository.ReviewHistoryFilter;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the recommended 15-minute daily routine (#111) into one guided workflow instead of several
 * screens the learner has to navigate and sequence themselves. This is a thin coordinator over
 * services already built for #99/#102/#103/#104/#109/#110 — it never recomputes session composition,
 * leech detection, reflection generation, or assessment selection itself, only decides which stage
 * comes next and assembles their outputs into one plan/summary.
 */
public class GuidedTrainingService {
    public static final int MIN_SESSION_MINUTES = 5;
    public static final int MAX_SESSION_MINUTES = 45;
    /** A run more than this many days old no longer counts as "already done this week" (#104). */
    static final int WEEKLY_ASSESSMENT_INTERVAL_DAYS = 7;
    /** Below this much leftover budget, reflection/assessment are flagged as unlikely to fit rather
     *  than silently offered as if there were plenty of time. */
    static final int MIN_OPTIONAL_STAGE_SECONDS = 60;

    private final ReviewService reviewService;
    private final StatsService statsService;
    private final WeeklyAssessmentSelectionService weeklyAssessmentSelectionService;
    private final AssessmentStatsService assessmentStatsService;
    private final FlashcardService flashcardService;
    private final ReviewHistoryRepository reviewHistoryRepository;
    private final UserProgressRepository userProgressRepository;

    public GuidedTrainingService() {
        this(new ReviewService(), new StatsService(), new WeeklyAssessmentSelectionService(),
                new AssessmentStatsService(), new FlashcardService(), new ReviewHistoryRepository(),
                new UserProgressRepository());
    }

    GuidedTrainingService(ReviewService reviewService, StatsService statsService,
                          WeeklyAssessmentSelectionService weeklyAssessmentSelectionService,
                          AssessmentStatsService assessmentStatsService, FlashcardService flashcardService,
                          ReviewHistoryRepository reviewHistoryRepository, UserProgressRepository userProgressRepository) {
        this.reviewService = reviewService;
        this.statsService = statsService;
        this.weeklyAssessmentSelectionService = weeklyAssessmentSelectionService;
        this.assessmentStatsService = assessmentStatsService;
        this.flashcardService = flashcardService;
        this.reviewHistoryRepository = reviewHistoryRepository;
        this.userProgressRepository = userProgressRepository;
    }

    public int getPreferredSessionMinutes() {
        return userProgressRepository.getProgress().getGuidedSessionMinutes();
    }

    public void setPreferredSessionMinutes(int minutes) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setGuidedSessionMinutes(clampSessionMinutes(minutes));
        userProgressRepository.save(progress);
    }

    public int getPreferredDailyNewCardLimit() {
        return userProgressRepository.getProgress().getDailyNewCardLimit();
    }

    /** Persists the cap {@link ReviewService}'s no-arg constructor reads on every subsequent screen,
     *  so changing it here changes the routine without the learner ever touching a queue directly. */
    public void setPreferredDailyNewCardLimit(int limit) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setDailyNewCardLimit(Math.max(0, limit));
        userProgressRepository.save(progress);
    }

    /**
     * Builds today's plan: due/relearning cards always take priority, new cards are capped and
     * focus-biased, and the weekly assessment is only included when it's actually due — every knob
     * the issue calls out (time budget, new-card limit, focus module, due-card priority) is applied
     * by {@link ReviewService#getAdaptiveSessionCards}, not recomputed here.
     */
    public GuidedTrainingPlan buildPlan(int sessionMinutes) {
        int clampedMinutes = clampSessionMinutes(sessionMinutes);
        ReviewService.AdaptiveSessionPlan reviewPlan = reviewService.getAdaptiveSessionCards(clampedMinutes);
        int cardsNeedingRewrite = statsService.getCardStateBreakdown().leechCards();

        List<WeeklyAssessmentSelectionService.SelectedAssessment> candidateItems =
                weeklyAssessmentSelectionService.selectWeeklyAssessment();
        boolean weeklyAssessmentDue = isWeeklyAssessmentDue(LocalDate.now(),
                assessmentStatsService.getLatestRunSummary(), !candidateItems.isEmpty());

        return new GuidedTrainingPlan(clampedMinutes, reviewPlan, !reviewPlan.cards().isEmpty(),
                hasTimeForOptionalStages(clampedMinutes, reviewPlan.estimatedSeconds()), cardsNeedingRewrite,
                weeklyAssessmentDue, weeklyAssessmentDue ? candidateItems : List.of());
    }

    /** Which stage the guided screen should show next, given which stages the learner already
     *  finished or explicitly skipped this run. Resuming after leaving mid-routine (or after a
     *  stage that turned out to have nothing to do) lands here rather than restarting from review. */
    public GuidedStage resolveCurrentStage(Set<GuidedStage> completedStages, GuidedTrainingPlan plan) {
        return resolveCurrentStage(completedStages, plan.weeklyAssessmentDue());
    }

    static GuidedStage resolveCurrentStage(Set<GuidedStage> completedStages, boolean weeklyAssessmentDue) {
        if (!completedStages.contains(GuidedStage.REVIEW)) {
            return GuidedStage.REVIEW;
        }
        if (!completedStages.contains(GuidedStage.REFLECTION)) {
            return GuidedStage.REFLECTION;
        }
        if (weeklyAssessmentDue && !completedStages.contains(GuidedStage.WEEKLY_ASSESSMENT)) {
            return GuidedStage.WEEKLY_ASSESSMENT;
        }
        return GuidedStage.COMPLETE;
    }

    /**
     * Only due once a full week has passed since the last run and there is content to serve; an
     * empty assessment bank never counts as "due" no matter how long it's been (#104).
     */
    static boolean isWeeklyAssessmentDue(LocalDate today, Optional<AssessmentRunSummary> latestRun, boolean itemsAvailable) {
        if (!itemsAvailable) {
            return false;
        }
        return latestRun.map(run -> run.runDate().isBefore(today.minusDays(WEEKLY_ASSESSMENT_INTERVAL_DAYS - 1)))
                .orElse(true);
    }

    /** Leftover budget after the review queue is what's realistically left for reflection/assessment;
     *  a queue that already fills the whole session leaves none. */
    static boolean hasTimeForOptionalStages(int sessionMinutes, int reviewEstimatedSeconds) {
        int leftoverSeconds = Math.max(0, sessionMinutes) * 60 - reviewEstimatedSeconds;
        return leftoverSeconds >= MIN_OPTIONAL_STAGE_SECONDS;
    }

    static int clampSessionMinutes(int minutes) {
        return Math.max(MIN_SESSION_MINUTES, Math.min(MAX_SESSION_MINUTES, minutes));
    }

    /**
     * Today's completion summary, read straight from persisted state rather than anything tracked
     * in-memory across screens: every review is already saved via {@link ReviewService#recordReview}
     * as it happens, so this reflects the full day's work even if reflection/assessment were skipped
     * or the app was restarted mid-routine.
     */
    public GuidedTrainingSummary buildCompletionSummary() {
        LocalDate today = LocalDate.now();
        List<ReviewHistory> todaysReviews = reviewHistoryRepository.findFiltered(
                new ReviewHistoryFilter(today.atStartOfDay(), LocalDateTime.now(), null, null, null));
        int newlyCaptured = countReflectionCardsCreatedOn(today);
        Optional<AssessmentRunSummary> todaysAssessmentRun = assessmentStatsService.getLatestRunSummary()
                .filter(run -> run.runDate().equals(today));
        return buildCompletionSummary(todaysReviews, newlyCaptured, todaysAssessmentRun);
    }

    private int countReflectionCardsCreatedOn(LocalDate day) {
        return (int) flashcardService.getAllCards().stream()
                .filter(card -> card.getCreatedAt() != null && card.getCreatedAt().toLocalDate().equals(day))
                .filter(GuidedTrainingService::isReflectionCard)
                .count();
    }

    private static boolean isReflectionCard(Flashcard card) {
        String skillCategory = card.getSkillCategory();
        return skillCategory != null && skillCategory.startsWith("Reflection:");
    }

    /**
     * Pure aggregation over a day's reviews so it's directly unit testable. "Retained" and "missed"
     * share the same success/failure signal every other efficiency metric uses
     * ({@link ReviewHistory#isSuccessfulAttempt}); "recovered" reuses
     * {@link StatsService#computeRecoveredMisses} so the definition matches the Progress screen's
     * own figure instead of inventing a second one.
     */
    static GuidedTrainingSummary buildCompletionSummary(List<ReviewHistory> todaysReviews, int newlyCapturedCount,
                                                        Optional<AssessmentRunSummary> todaysAssessmentRun) {
        int retained = (int) todaysReviews.stream().filter(ReviewHistory::isSuccessfulAttempt).count();
        int missed = todaysReviews.size() - retained;
        int recovered = StatsService.computeRecoveredMisses(todaysReviews).recoveredCount();

        boolean assessmentTaken = todaysAssessmentRun.isPresent();
        int assessmentCorrect = todaysAssessmentRun.map(AssessmentRunSummary::correctCount).orElse(0);
        int assessmentTotal = todaysAssessmentRun.map(AssessmentRunSummary::totalItems).orElse(0);

        return new GuidedTrainingSummary(todaysReviews.size(), retained, missed, recovered, newlyCapturedCount,
                assessmentTaken, assessmentCorrect, assessmentTotal);
    }
}
