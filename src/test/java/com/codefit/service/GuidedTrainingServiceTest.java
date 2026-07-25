package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CardState;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.DeckRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the orchestration logic of the guided daily routine (#111): stage sequencing, adapting to
 * no-due-cards/no-reflection/insufficient-time, offering the weekly assessment only when due, and
 * the completion summary's counts. Session composition itself (due/relearning-first, focus-biased
 * new cards, mature interleaving) is already covered by {@code ReviewServiceTest} and deliberately
 * not re-tested here, since {@link GuidedTrainingService} only delegates to it.
 */
class GuidedTrainingServiceTest {

    private final GuidedTrainingService guidedTrainingService = new GuidedTrainingService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private ReviewHistory review(long flashcardId, ReviewRating rating, String validationResult, String sessionId) {
        return new ReviewHistory(0, flashcardId, rating, 0, 1, LocalDateTime.now(), true, false,
                validationResult, "attempt", 1000, false, sessionId);
    }

    // ---- stage sequencing ----

    @Test
    void firstStageIsAlwaysReview() {
        assertEquals(GuidedStage.REVIEW, GuidedTrainingService.resolveCurrentStage(Set.of(), true));
        assertEquals(GuidedStage.REVIEW, GuidedTrainingService.resolveCurrentStage(Set.of(), false));
    }

    @Test
    void reflectionFollowsReviewRegardlessOfWeeklyAssessment() {
        Set<GuidedStage> afterReview = EnumSet.of(GuidedStage.REVIEW);
        assertEquals(GuidedStage.REFLECTION, GuidedTrainingService.resolveCurrentStage(afterReview, true));
        assertEquals(GuidedStage.REFLECTION, GuidedTrainingService.resolveCurrentStage(afterReview, false));
    }

    @Test
    void weeklyAssessmentStageIsSkippedWhenNotDue() {
        Set<GuidedStage> afterReflection = EnumSet.of(GuidedStage.REVIEW, GuidedStage.REFLECTION);

        assertEquals(GuidedStage.WEEKLY_ASSESSMENT, GuidedTrainingService.resolveCurrentStage(afterReflection, true),
                "due this week: the routine must offer it before completing");
        assertEquals(GuidedStage.COMPLETE, GuidedTrainingService.resolveCurrentStage(afterReflection, false),
                "not due: the routine must skip straight to completion");
    }

    @Test
    void routineCompletesOnceEveryApplicableStageIsDone() {
        Set<GuidedStage> allDone = EnumSet.of(GuidedStage.REVIEW, GuidedStage.REFLECTION, GuidedStage.WEEKLY_ASSESSMENT);
        assertEquals(GuidedStage.COMPLETE, GuidedTrainingService.resolveCurrentStage(allDone, true));
    }

    // ---- adapting to no due cards / insufficient time ----

    @Test
    void hasTimeForOptionalStagesIsTrueWhenReviewLeavesMeaningfulBudget() {
        assertTrue(GuidedTrainingService.hasTimeForOptionalStages(15, 0), "nothing due yet: the whole budget is left over");
        assertTrue(GuidedTrainingService.hasTimeForOptionalStages(15, 10 * 60));
    }

    @Test
    void hasTimeForOptionalStagesIsFalseWhenReviewConsumesTheWholeBudget() {
        assertFalse(GuidedTrainingService.hasTimeForOptionalStages(15, 15 * 60),
                "a fully packed queue leaves nothing for the optional stages");
        assertFalse(GuidedTrainingService.hasTimeForOptionalStages(5, 5 * 60 - 10),
                "a few leftover seconds isn't a realistic amount of extra time");
    }

    @Test
    void sessionMinutesAreClampedToASaneRange() {
        assertEquals(GuidedTrainingService.MIN_SESSION_MINUTES, GuidedTrainingService.clampSessionMinutes(0));
        assertEquals(GuidedTrainingService.MIN_SESSION_MINUTES, GuidedTrainingService.clampSessionMinutes(-5));
        assertEquals(GuidedTrainingService.MAX_SESSION_MINUTES, GuidedTrainingService.clampSessionMinutes(500));
        assertEquals(15, GuidedTrainingService.clampSessionMinutes(15));
    }

    // ---- weekly assessment offered only when due ----

    @Test
    void weeklyAssessmentIsNeverDueWithoutAvailableItems() {
        assertFalse(GuidedTrainingService.isWeeklyAssessmentDue(LocalDate.now(), Optional.empty(), false),
                "an empty assessment bank can never be 'due' no matter how long it's been");
    }

    @Test
    void weeklyAssessmentIsDueTheFirstTimeWithNoPriorRun() {
        assertTrue(GuidedTrainingService.isWeeklyAssessmentDue(LocalDate.now(), Optional.empty(), true));
    }

    @Test
    void weeklyAssessmentIsNotDueWithinTheSameWeekAsTheLastRun() {
        LocalDate today = LocalDate.now();
        AssessmentRunSummary sixDaysAgo = new AssessmentRunSummary("run-1", today.minusDays(6), 8, 6, List.of());

        assertFalse(GuidedTrainingService.isWeeklyAssessmentDue(today, Optional.of(sixDaysAgo), true));
    }

    @Test
    void weeklyAssessmentIsDueOnceAFullWeekHasPassedSinceTheLastRun() {
        LocalDate today = LocalDate.now();
        AssessmentRunSummary sevenDaysAgo = new AssessmentRunSummary("run-1", today.minusDays(7), 8, 6, List.of());

        assertTrue(GuidedTrainingService.isWeeklyAssessmentDue(today, Optional.of(sevenDaysAgo), true));
    }

    // ---- completion summary counts ----

    @Test
    void completionSummaryCountsRetainedMissedRecoveredAndCaptured() {
        List<ReviewHistory> todaysReviews = List.of(
                review(1, ReviewRating.GOOD, "EXACT", "s1"),
                review(2, ReviewRating.AGAIN, "DIFFERENT", "s1"),
                review(3, ReviewRating.EASY, "EXACT", "s1"),
                review(2, ReviewRating.GOOD, "EXACT", "s1")   // card 2 recovered after its earlier miss
        );

        GuidedTrainingSummary summary = GuidedTrainingService.buildCompletionSummary(todaysReviews, 3, Optional.empty());

        assertEquals(4, summary.cardsReviewed());
        assertEquals(3, summary.retainedCount());
        assertEquals(1, summary.missedCount());
        assertEquals(1, summary.recoveredCount());
        assertEquals(3, summary.newlyCapturedCount());
        assertFalse(summary.weeklyAssessmentTaken());
        assertEquals(0, summary.weeklyAssessmentTotal());
    }

    @Test
    void completionSummaryReportsTodaysWeeklyAssessmentResultWhenTaken() {
        AssessmentRunSummary todaysRun = new AssessmentRunSummary("run-today", LocalDate.now(), 8, 5, List.of());

        GuidedTrainingSummary summary = GuidedTrainingService.buildCompletionSummary(List.of(), 0, Optional.of(todaysRun));

        assertTrue(summary.weeklyAssessmentTaken());
        assertEquals(5, summary.weeklyAssessmentCorrect());
        assertEquals(8, summary.weeklyAssessmentTotal());
    }

    @Test
    void completionSummaryAdaptsToAnEntirelyEmptyDay() {
        GuidedTrainingSummary summary = GuidedTrainingService.buildCompletionSummary(List.of(), 0, Optional.empty());

        assertEquals(0, summary.cardsReviewed());
        assertEquals(0, summary.retainedCount());
        assertEquals(0, summary.missedCount());
        assertEquals(0, summary.recoveredCount());
        assertEquals(0, summary.newlyCapturedCount());
        assertFalse(summary.weeklyAssessmentTaken());
    }

    // ---- review results persisted independent of later stages being skipped ----

    @Test
    void completionSummaryReflectsReviewsSavedRegardlessOfWhetherReflectionOrAssessmentRan() {
        String deckName = "Java BE 01 - Core Java & OOP";
        String testFront = "TEST-FIXTURE: GuidedTrainingServiceTest completion summary card";

        DeckRepository deckRepository = new DeckRepository();
        Deck deck = deckRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(deckName))
                .findFirst()
                .orElseThrow();

        FlashcardService flashcardService = new FlashcardService();
        if (!flashcardService.cardExistsInDeck(deck.getId(), testFront)) {
            flashcardService.addCard(deck.getId(), testFront, "answer");
        }
        Flashcard card = flashcardService.getAllCards().stream()
                .filter(candidate -> candidate.getDeckId() == deck.getId() && testFront.equals(candidate.getFront()))
                .findFirst()
                .orElseThrow();
        // Reset so repeated local runs of this test always exercise a fresh, in-time review.
        card.setCardState(CardState.REVIEW);
        card.setDueDate(LocalDate.now());

        GuidedTrainingSummary before = guidedTrainingService.buildCompletionSummary();

        // Recording a review is the normal ReviewService path used by the Review screen; nothing
        // about the guided routine's reflection/assessment stages is touched here, matching a
        // learner who reviews then leaves without capturing a reflection or taking the assessment.
        ReviewService reviewService = new ReviewService();
        reviewService.review(card, ReviewRating.GOOD, true,
                new ReviewAttempt("EXACT", "answer", 4000, false, "guided-training-test-session"));

        GuidedTrainingSummary after = guidedTrainingService.buildCompletionSummary();

        assertEquals(before.cardsReviewed() + 1, after.cardsReviewed());
        assertEquals(before.retainedCount() + 1, after.retainedCount());
    }
}
