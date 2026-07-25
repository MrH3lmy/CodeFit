package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import com.codefit.service.CardLifecycleService.LeechThresholds;
import com.codefit.service.MasteryService.CardMasteryState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardLifecycleServiceTest {

    private final CardLifecycleService service = new CardLifecycleService();

    private Flashcard card(CardState state) {
        Flashcard flashcard = new Flashcard(1, "front", "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setCardState(state);
        return flashcard;
    }

    private ReviewHistory history(ReviewRating rating, boolean hintUsed, Integer responseTimeMs, String validationResult) {
        return new ReviewHistory(0, 1, rating, 0, 0, LocalDateTime.now(), true, false,
                validationResult, "attempt", responseTimeMs, hintUsed, "session");
    }

    private ReviewHistory again() {
        return history(ReviewRating.AGAIN, false, 1000, "DIFFERENT");
    }

    private ReviewHistory good() {
        return history(ReviewRating.GOOD, false, 1000, "EXACT");
    }

    @Test
    void newCardRatedAgainStaysLearningNotRelearning() {
        Flashcard card = card(CardState.NEW);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.NOT_SEEN);
        assertEquals(CardState.LEARNING, card.getCardState());
    }

    @Test
    void learningCardRatedAgainStaysLearning() {
        Flashcard card = card(CardState.LEARNING);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.LEARNING, card.getCardState());
    }

    @Test
    void reviewCardRatedAgainMovesToRelearning() {
        Flashcard card = card(CardState.REVIEW);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void masteredCardRatedAgainMovesToRelearning() {
        Flashcard card = card(CardState.MASTERED);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void relearningCardRatedAgainStaysRelearning() {
        Flashcard card = card(CardState.RELEARNING);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void passingRatingGraduatesToReviewWhenNotYetMastered() {
        Flashcard newCard = card(CardState.NEW);
        service.applyReviewOutcome(newCard, ReviewRating.GOOD, CardMasteryState.LEARNING);
        assertEquals(CardState.REVIEW, newCard.getCardState());

        Flashcard relearningCard = card(CardState.RELEARNING);
        service.applyReviewOutcome(relearningCard, ReviewRating.HARD, CardMasteryState.LEARNING);
        assertEquals(CardState.REVIEW, relearningCard.getCardState());
    }

    @Test
    void passingRatingMovesToMasteredWhenMasteryServiceSaysSo() {
        Flashcard card = card(CardState.REVIEW);
        service.applyReviewOutcome(card, ReviewRating.EASY, CardMasteryState.MASTERED);
        assertEquals(CardState.MASTERED, card.getCardState());
    }

    @Test
    void suspendedCardIsNeverReactivatedByAReview() {
        Flashcard card = card(CardState.SUSPENDED);
        service.applyReviewOutcome(card, ReviewRating.EASY, CardMasteryState.MASTERED);
        assertEquals(CardState.SUSPENDED, card.getCardState());

        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.NOT_SEEN);
        assertEquals(CardState.SUSPENDED, card.getCardState());
    }

    @Test
    void introducedAtIsSetOnceOnFirstReviewAndNeverOverwritten() {
        Flashcard card = card(CardState.NEW);
        assertNull(card.getIntroducedAt());

        service.applyReviewOutcome(card, ReviewRating.GOOD, CardMasteryState.LEARNING);
        assertNotNull(card.getIntroducedAt());
        var firstIntroducedAt = card.getIntroducedAt();

        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(firstIntroducedAt, card.getIntroducedAt());
    }

    @Test
    void suspendedCardIntroducedAtIsNotSet() {
        Flashcard card = card(CardState.SUSPENDED);
        service.applyReviewOutcome(card, ReviewRating.GOOD, CardMasteryState.MASTERED);
        assertNull(card.getIntroducedAt());
    }

    @Test
    void graduatingANewCardSetsStateAndIntroducedAt() {
        Flashcard card = card(CardState.NEW);
        assertNull(card.getIntroducedAt());

        service.graduate(card, 45);

        assertEquals(CardState.GRADUATED, card.getCardState());
        assertNotNull(card.getIntroducedAt());
        assertEquals(45, card.getIntervalDays());
        assertEquals(LocalDate.now().plusDays(45), card.getDueDate());
        assertEquals(1, card.getReviewCount());
    }

    @Test
    void graduationIntervalIsClampedToTheConfiguredRange() {
        Flashcard tooShort = card(CardState.NEW);
        service.graduate(tooShort, 5);
        assertEquals(CardLifecycleService.MIN_GRADUATION_INTERVAL_DAYS, tooShort.getIntervalDays());

        Flashcard tooLong = card(CardState.NEW);
        service.graduate(tooLong, 200);
        assertEquals(CardLifecycleService.MAX_GRADUATION_INTERVAL_DAYS, tooLong.getIntervalDays());
    }

    @Test
    void graduatingASuspendedCardIsANoOp() {
        Flashcard card = card(CardState.SUSPENDED);
        service.graduate(card, 45);
        assertEquals(CardState.SUSPENDED, card.getCardState());
        assertNull(card.getIntroducedAt());
    }

    @Test
    void graduatedCardRatedAgainOnRetentionCheckMovesToRelearning() {
        // The retention check when a graduated card comes back due must go through the same
        // AGAIN-handling as any other previously-reviewed card, not be treated like a fresh NEW card.
        Flashcard card = card(CardState.GRADUATED);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.NOT_SEEN);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void graduatedCardPassingRetentionCheckMovesToReviewOrMastered() {
        Flashcard reviewCard = card(CardState.GRADUATED);
        service.applyReviewOutcome(reviewCard, ReviewRating.GOOD, CardMasteryState.LEARNING);
        assertEquals(CardState.REVIEW, reviewCard.getCardState());

        Flashcard masteredCard = card(CardState.GRADUATED);
        service.applyReviewOutcome(masteredCard, ReviewRating.EASY, CardMasteryState.MASTERED);
        assertEquals(CardState.MASTERED, masteredCard.getCardState());
    }

    @Test
    void suspendMarksAnyCardStateSuspended() {
        Flashcard newCard = card(CardState.NEW);
        service.suspend(newCard);
        assertEquals(CardState.SUSPENDED, newCard.getCardState());

        Flashcard reviewCard = card(CardState.REVIEW);
        service.suspend(reviewCard);
        assertEquals(CardState.SUSPENDED, reviewCard.getCardState());
    }

    // --- Leech detection and rewrite lifecycle (issue #103) ---

    @Test
    void isLeechRequiresAtLeastTheConfiguredAgainCount() {
        List<ReviewHistory> reviews = List.of(again(), again(), good(), good(), good());
        assertFalse(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechTripsOnceTheConfiguredAgainCountIsReachedWithinTheLookback() {
        List<ReviewHistory> reviews = List.of(again(), again(), again(), good(), good());
        assertTrue(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechIgnoresAgainsOutsideTheLookbackWindow() {
        // Only the 5 most-recent (lookbackReviews) count; older Agains further back must not count
        // toward the threshold even though there are plenty of them in the full history.
        List<ReviewHistory> reviews = List.of(good(), good(), good(), good(), good(),
                again(), again(), again(), again());
        assertFalse(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechTripsOnASustainedFailureRateEvenWithoutEnoughAgainsAlone() {
        // 3 of 4 attempts fail (rated Hard, not Again), so the again-count signal alone never
        // fires, but the failure-rate signal (>= 60% of at least 4 attempts) does.
        ReviewHistory failure = history(ReviewRating.HARD, false, 1000, "DIFFERENT");
        List<ReviewHistory> reviews = List.of(failure, failure, failure, good());
        assertTrue(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechDoesNotApplyFailureRateBeforeEnoughAttemptsAccumulate() {
        ReviewHistory failure = history(ReviewRating.HARD, false, 1000, "DIFFERENT");
        List<ReviewHistory> reviews = List.of(failure, failure); // 100% failure, but below minAttempts
        assertFalse(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechTripsOnRepeatedHintUseEvenWhenEveryRatingPasses() {
        ReviewHistory hinted = history(ReviewRating.GOOD, true, 1000, "EXACT");
        List<ReviewHistory> reviews = List.of(hinted, hinted, hinted, good());
        assertTrue(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechTripsOnResponsesConsistentlyFarSlowerThanTheCardsOwnTimeLimit() {
        Flashcard timedCard = new Flashcard(1, "front", "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null, 10);
        timedCard.setCardState(CardState.REVIEW);
        ReviewHistory slow = history(ReviewRating.GOOD, false, 25_000, "EXACT"); // 2.5x the 10s limit
        List<ReviewHistory> reviews = List.of(slow, slow, good());
        assertTrue(CardLifecycleService.isLeech(timedCard, reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechIgnoresResponseTimeForCardsWithoutATimeLimit() {
        ReviewHistory slow = history(ReviewRating.GOOD, false, 60_000, "EXACT");
        List<ReviewHistory> reviews = List.of(slow, slow, slow);
        assertFalse(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void isLeechThresholdsAreConfigurablePerCaller() {
        List<ReviewHistory> reviews = List.of(again(), good(), good());
        LeechThresholds strict = new LeechThresholds(5, 1, 4, 0.6, 3, 2.0, 2, 3);
        assertTrue(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, strict),
                "a caller-supplied threshold of 1 Again must flag the card even though the default threshold would not");
        assertFalse(CardLifecycleService.isLeech(card(CardState.REVIEW), reviews, CardLifecycleService.DEFAULT_LEECH_THRESHOLDS));
    }

    @Test
    void reviewCardRatedAgainThreeTimesInARowIsFlaggedLeechInsteadOfRelearning() {
        Flashcard reviewCard = card(CardState.REVIEW);
        List<ReviewHistory> recentHistory = List.of(again(), again(), again());

        service.applyReviewOutcome(reviewCard, ReviewRating.AGAIN, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.LEECH, reviewCard.getCardState());
    }

    @Test
    void aPassingRatingCanStillFlagLeechWhenHistoryShowsRepeatedHintUse() {
        // Evidence is about the card's track record, not just today's rating: a lucky Good today
        // doesn't erase a pattern of needing hints every time.
        Flashcard reviewCard = card(CardState.REVIEW);
        ReviewHistory hinted = history(ReviewRating.GOOD, true, 1000, "EXACT");
        List<ReviewHistory> recentHistory = List.of(good(), hinted, hinted, hinted);

        service.applyReviewOutcome(reviewCard, ReviewRating.GOOD, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.LEECH, reviewCard.getCardState());
    }

    @Test
    void insufficientEvidenceLeavesNormalLifecycleTransitionsUnaffected() {
        Flashcard reviewCard = card(CardState.REVIEW);
        List<ReviewHistory> recentHistory = List.of(again(), good(), good());

        service.applyReviewOutcome(reviewCard, ReviewRating.AGAIN, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.RELEARNING, reviewCard.getCardState());
    }

    @Test
    void threeArgOverloadNeverFlagsLeechSinceItHasNoHistoryToEvaluate() {
        Flashcard reviewCard = card(CardState.REVIEW);
        service.applyReviewOutcome(reviewCard, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, reviewCard.getCardState());
    }

    @Test
    void leechCardRatedAgainStaysLeechRegardlessOfHistory() {
        Flashcard leechCard = card(CardState.LEECH);
        List<ReviewHistory> recentHistory = List.of(good(), good(), good());

        service.applyReviewOutcome(leechCard, ReviewRating.AGAIN, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.LEECH, leechCard.getCardState());
    }

    @Test
    void leechCardLeavesLeechAfterTheConfiguredStreakOfConsecutiveSuccessfulReviews() {
        Flashcard leechCard = card(CardState.LEECH);
        // Newest-first: this review's own just-saved row plus the streak's prior two successes.
        List<ReviewHistory> recentHistory = List.of(good(), good(), good());

        service.applyReviewOutcome(leechCard, ReviewRating.GOOD, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.REVIEW, leechCard.getCardState());
    }

    @Test
    void leechCardCanRecoverStraightToMasteredWhenMasteryServiceSaysSo() {
        Flashcard leechCard = card(CardState.LEECH);
        List<ReviewHistory> recentHistory = List.of(good(), good(), good());

        service.applyReviewOutcome(leechCard, ReviewRating.EASY, CardMasteryState.MASTERED, recentHistory);

        assertEquals(CardState.MASTERED, leechCard.getCardState());
    }

    @Test
    void leechCardStaysLeechWithoutTheFullRecoveryStreak() {
        Flashcard leechCard = card(CardState.LEECH);
        List<ReviewHistory> recentHistory = List.of(good(), good()); // one short of the streak of 3

        service.applyReviewOutcome(leechCard, ReviewRating.GOOD, CardMasteryState.LEARNING, recentHistory);

        assertEquals(CardState.LEECH, leechCard.getCardState());
    }

    @Test
    void resetForRewriteRestartsSchedulingFromScratchAndClearsLeech() {
        Flashcard leechCard = card(CardState.LEECH);
        leechCard.setIntervalDays(30);
        leechCard.setEaseFactor(1.5);
        leechCard.setDueDate(LocalDate.now().plusDays(30));
        leechCard.setIntroducedAt(LocalDateTime.now().minusDays(10));
        var originalIntroducedAt = leechCard.getIntroducedAt();

        service.resetForRewrite(leechCard);

        assertEquals(CardState.NEW, leechCard.getCardState());
        assertEquals(0, leechCard.getIntervalDays());
        assertEquals(CardLifecycleService.DEFAULT_EASE_FACTOR, leechCard.getEaseFactor());
        assertEquals(LocalDate.now(), leechCard.getDueDate());
        // introducedAt tracks first introduction, not the rewrite, so it must be left untouched.
        assertEquals(originalIntroducedAt, leechCard.getIntroducedAt());
    }

    @Test
    void resetForRewriteWorksFromAnyState() {
        Flashcard reviewCard = card(CardState.REVIEW);
        service.resetForRewrite(reviewCard);
        assertEquals(CardState.NEW, reviewCard.getCardState());

        Flashcard suspendedCard = card(CardState.SUSPENDED);
        service.resetForRewrite(suspendedCard);
        assertEquals(CardState.NEW, suspendedCard.getCardState());
    }
}
