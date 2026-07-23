package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import com.codefit.service.MasteryService.CardMasteryState;
import com.codefit.service.MasteryService.MasteryThresholds;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasteryServiceTest {

    private static final MasteryThresholds THRESHOLDS = new MasteryThresholds(2, 14, 3);

    private Flashcard card(int intervalDays, int reviewCount, Integer timeLimitSeconds) {
        Flashcard flashcard = new Flashcard(1, "front", "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null, timeLimitSeconds);
        flashcard.setId(1);
        flashcard.setIntervalDays(intervalDays);
        flashcard.setReviewCount(reviewCount);
        return flashcard;
    }

    private ReviewHistory review(ReviewRating rating, String validationResult, Integer responseTimeMs) {
        return new ReviewHistory(0, 1, rating, 0, 0, LocalDateTime.now(), true, false,
                validationResult, "attempt", responseTimeMs, false, "session");
    }

    private Flashcard conceptCard(int intervalDays, int reviewCount) {
        Flashcard flashcard = new Flashcard(1, "front", "back", CardType.CONCEPT, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(1);
        flashcard.setIntervalDays(intervalDays);
        flashcard.setReviewCount(reviewCount);
        return flashcard;
    }

    private ReviewHistory subjectiveReview(ReviewRating rating) {
        return new ReviewHistory(0, 1, rating, 0, 0, LocalDateTime.now(), true, false,
                "SUBJECTIVE", "explanation in my own words", 8000, false, "session");
    }

    @Test
    void newCardWithNoReviewsIsNotSeen() {
        Flashcard newCard = card(0, 0, null);
        assertEquals(CardMasteryState.NOT_SEEN, MasteryService.evaluate(newCard, List.of(), THRESHOLDS));
    }

    @Test
    void singleAttemptIsLearningNotMastered() {
        Flashcard attemptedOnce = card(1, 1, null);
        List<ReviewHistory> history = List.of(review(ReviewRating.GOOD, "EXACT", 1000));
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(attemptedOnce, history, THRESHOLDS));
    }

    @Test
    void failedAttemptsKeepCardInLearningNeverMastered() {
        Flashcard card = card(20, 3, null);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.EASY, "DIFFERENT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000)
        );
        // Most recent review (index 0) was objectively wrong despite a self-rated EASY, so the
        // card must not be reported as mastered.
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(card, history, THRESHOLDS));
    }

    @Test
    void meetsAllCriteriaIsMastered() {
        Flashcard card = card(14, 4, null);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000)
        );
        assertEquals(CardMasteryState.MASTERED, MasteryService.evaluate(card, history, THRESHOLDS));
    }

    @Test
    void intervalBelowThresholdIsNotMasteredEvenIfRecentReviewsAreCorrect() {
        Flashcard card = card(5, 3, null);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(card, history, THRESHOLDS));
    }

    @Test
    void lapsedCardWithRecentAgainInWindowIsNotMastered() {
        Flashcard card = card(20, 5, null);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.AGAIN, "DIFFERENT", 1000)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(card, history, THRESHOLDS));
    }

    @Test
    void againOutsideTheWindowDoesNotBlockMastery() {
        Flashcard card = card(20, 6, null);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.GOOD, "EXACT", 1000),
                review(ReviewRating.AGAIN, "DIFFERENT", 1000)
        );
        assertEquals(CardMasteryState.MASTERED, MasteryService.evaluate(card, history, THRESHOLDS));
    }

    @Test
    void responseTimeOverTimeLimitBlocksMastery() {
        Flashcard timedCard = card(20, 3, 10);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 15_000),
                review(ReviewRating.GOOD, "EXACT", 5_000)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(timedCard, history, THRESHOLDS));
    }

    @Test
    void responseTimeWithinLimitAllowsMastery() {
        Flashcard timedCard = card(20, 3, 10);
        List<ReviewHistory> history = List.of(
                review(ReviewRating.GOOD, "EXACT", 5_000),
                review(ReviewRating.GOOD, "EXACT", 5_000)
        );
        assertEquals(CardMasteryState.MASTERED, MasteryService.evaluate(timedCard, history, THRESHOLDS));
    }

    @Test
    void conceptCardWithDifferentWordingIsSubjectiveNotObjectivelyWrong() {
        // A concept answer written in the learner's own words is never text-matched: the stored
        // validation result is SUBJECTIVE, not DIFFERENT, regardless of wording.
        ReviewHistory differentWording = subjectiveReview(ReviewRating.GOOD);
        assertEquals(true, differentWording.isSubjective());
        assertEquals(false, differentWording.isObjectivelyCorrect());
    }

    @Test
    void subjectiveConceptCardReachesMasteryViaConsecutiveGoodOrEasyRatings() {
        Flashcard concept = conceptCard(14, 3);
        List<ReviewHistory> history = List.of(
                subjectiveReview(ReviewRating.GOOD),
                subjectiveReview(ReviewRating.EASY)
        );
        assertEquals(CardMasteryState.MASTERED, MasteryService.evaluate(concept, history, THRESHOLDS));
    }

    @Test
    void subjectiveConceptCardBelowIntervalThresholdIsNotMastered() {
        Flashcard concept = conceptCard(5, 3);
        List<ReviewHistory> history = List.of(
                subjectiveReview(ReviewRating.GOOD),
                subjectiveReview(ReviewRating.EASY)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(concept, history, THRESHOLDS));
    }

    @Test
    void subjectiveAgainRatingBlocksMastery() {
        Flashcard concept = conceptCard(20, 3);
        List<ReviewHistory> history = List.of(
                subjectiveReview(ReviewRating.AGAIN),
                subjectiveReview(ReviewRating.GOOD)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(concept, history, THRESHOLDS));
    }

    @Test
    void subjectiveHardRatingBlocksMastery() {
        Flashcard concept = conceptCard(20, 3);
        List<ReviewHistory> history = List.of(
                subjectiveReview(ReviewRating.HARD),
                subjectiveReview(ReviewRating.EASY)
        );
        assertEquals(CardMasteryState.LEARNING, MasteryService.evaluate(concept, history, THRESHOLDS));
    }

    @Test
    void subjectiveConceptCardNeverUsesObjectiveMasteryRule() {
        // Even if the (irrelevant) validation_result happened to look "correct", concept cards
        // must go through the subjective rule, which only cares about the rating.
        Flashcard concept = conceptCard(14, 2);
        List<ReviewHistory> allEasySubjective = List.of(
                subjectiveReview(ReviewRating.EASY),
                subjectiveReview(ReviewRating.EASY)
        );
        assertEquals(CardMasteryState.MASTERED, MasteryService.evaluate(concept, allEasySubjective, THRESHOLDS));
    }

    @Test
    void summarizeAggregatesBreakdownAcrossCards() {
        MasteryService.MasteryBreakdown breakdown = new MasteryService.MasteryBreakdown(10, 6, 4, 2);
        assertEquals(60.0, breakdown.seenPercent());
        assertEquals(40.0, breakdown.learningPercent());
        assertEquals(20.0, breakdown.masteredPercent());
    }

    @Test
    void emptyBreakdownAvoidsDivideByZero() {
        MasteryService.MasteryBreakdown breakdown = new MasteryService.MasteryBreakdown(0, 0, 0, 0);
        assertEquals(0.0, breakdown.seenPercent());
        assertEquals(0.0, breakdown.masteredPercent());
    }
}
