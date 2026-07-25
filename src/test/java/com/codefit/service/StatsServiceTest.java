package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsServiceTest {

    private ReviewHistory objective(ReviewRating rating, String validationResult, boolean submittedInTime) {
        return new ReviewHistory(0, 1, rating, 0, 1, LocalDateTime.now(), submittedInTime, false,
                validationResult, "attempt", 1000, false, "session");
    }

    private ReviewHistory subjective(ReviewRating rating) {
        return new ReviewHistory(0, 2, rating, 0, 30, LocalDateTime.now(), true, false,
                "SUBJECTIVE", "my own explanation in different words", 4000, false, "session");
    }

    private ReviewHistory objectiveWithConfidence(String validationResult, String confidence) {
        return new ReviewHistory(0, 1, ReviewRating.GOOD, 0, 1, LocalDateTime.now(), true, false,
                validationResult, "attempt", 1000, false, "session", confidence);
    }

    @Test
    void subjectiveCardsDoNotReduceObjectiveAccuracy() {
        // Two objective reviews, both correct, plus a subjective review rated Good with different
        // wording. The subjective attempt has no text-match signal and must not be treated as a
        // miss that drags accuracy down.
        List<ReviewHistory> reviews = List.of(
                objective(ReviewRating.GOOD, "EXACT", true),
                objective(ReviewRating.GOOD, "EXACT", true),
                subjective(ReviewRating.GOOD)
        );

        assertEquals(100.0, StatsService.getOverallRecentAccuracy(reviews));
    }

    @Test
    void mixedObjectiveAndSubjectiveStatisticsAreComputedIndependently() {
        List<ReviewHistory> reviews = List.of(
                objective(ReviewRating.GOOD, "EXACT", true),   // objective correct, timed success
                objective(ReviewRating.AGAIN, "DIFFERENT", true), // objective incorrect
                subjective(ReviewRating.EASY),                 // subjective self-rated pass
                subjective(ReviewRating.AGAIN)                 // subjective self-rated fail
        );

        // Objective accuracy: 1 of 2 objectively-graded reviews correct.
        assertEquals(50.0, StatsService.getOverallRecentAccuracy(reviews));
        // Timed success also only considers the objectively-graded reviews.
        assertEquals(50.0, StatsService.getTimedSuccessRate(reviews));
        // Subjective self-assessment: 1 of 2 subjective reviews self-rated Good/Easy.
        assertEquals(50.0, StatsService.getSubjectiveSelfAssessmentRate(reviews));
    }

    @Test
    void allSubjectiveReviewsGiveZeroObjectiveAccuracyAndZeroTimedSuccessNotDivideByZero() {
        List<ReviewHistory> reviews = List.of(subjective(ReviewRating.GOOD), subjective(ReviewRating.EASY));

        assertEquals(0.0, StatsService.getOverallRecentAccuracy(reviews));
        assertEquals(0.0, StatsService.getTimedSuccessRate(reviews));
        assertEquals(100.0, StatsService.getSubjectiveSelfAssessmentRate(reviews));
    }

    @Test
    void allObjectiveReviewsGiveZeroSubjectiveRateNotDivideByZero() {
        List<ReviewHistory> reviews = List.of(objective(ReviewRating.GOOD, "EXACT", true));

        assertEquals(0.0, StatsService.getSubjectiveSelfAssessmentRate(reviews));
    }

    @Test
    void emptyReviewListGivesZeroForAllRatesNotDivideByZero() {
        assertEquals(0.0, StatsService.getOverallRecentAccuracy(List.of()));
        assertEquals(0.0, StatsService.getTimedSuccessRate(List.of()));
        assertEquals(0.0, StatsService.getSubjectiveSelfAssessmentRate(List.of()));
    }

    @Test
    void highConfidenceCorrectAndLowConfidenceIncorrectAreCalibrated() {
        List<ReviewHistory> reviews = List.of(
                objectiveWithConfidence("EXACT", "HIGH"),    // confident and right: calibrated
                objectiveWithConfidence("DIFFERENT", "LOW")  // unsure and wrong: calibrated
        );

        assertEquals(100.0, StatsService.getConfidenceCalibrationScore(reviews));
        assertEquals(2, StatsService.getConfidenceSampleCount(reviews));
    }

    @Test
    void highConfidenceIncorrectAndLowConfidenceCorrectAreMiscalibrated() {
        List<ReviewHistory> reviews = List.of(
                objectiveWithConfidence("DIFFERENT", "HIGH"), // confident but wrong: overconfident
                objectiveWithConfidence("EXACT", "LOW")       // unsure but right: underconfident
        );

        assertEquals(0.0, StatsService.getConfidenceCalibrationScore(reviews));
        assertEquals(2, StatsService.getConfidenceSampleCount(reviews));
    }

    @Test
    void confidenceCalibrationExcludesMediumConfidenceAndSubjectiveAttempts() {
        List<ReviewHistory> reviews = List.of(
                objectiveWithConfidence("EXACT", "MEDIUM"),
                objectiveWithConfidence("EXACT", null),
                subjective(ReviewRating.GOOD)
        );

        assertEquals(0.0, StatsService.getConfidenceCalibrationScore(reviews));
        assertEquals(0, StatsService.getConfidenceSampleCount(reviews));
    }

    @Test
    void confidenceCalibrationScoreNeverDerivedFromSchedulerRatingAlone() {
        // Scheduler rating is GOOD in every case (see objectiveWithConfidence); only the recorded
        // validation result and confidence should drive the calibration outcome.
        List<ReviewHistory> reviews = List.of(objectiveWithConfidence("DIFFERENT", "HIGH"));

        assertEquals(0.0, StatsService.getConfidenceCalibrationScore(reviews));
    }

    // --- Learning-efficiency metrics (issue #109) ---

    private ReviewHistory review(long id, long flashcardId, ReviewRating rating, String validationResult,
                                  int previousIntervalDays, Integer responseTimeMs, String sessionId, LocalDateTime reviewedAt) {
        return new ReviewHistory(id, flashcardId, rating, previousIntervalDays, previousIntervalDays, reviewedAt,
                true, false, validationResult, "attempt", responseTimeMs, false, sessionId, null);
    }

    private Flashcard flashcard(long id, String skillCategory, CardType cardType) {
        Flashcard card = new Flashcard(id, 1, "front", "back", LocalDate.now(), 0, 2.5, 1, LocalDateTime.now());
        card.setSkillCategory(skillCategory);
        card.setCardType(cardType);
        return card;
    }

    @Test
    void totalActiveMinutesSumsResponseTimesAndIgnoresNulls() {
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 1, 30_000, "s1", LocalDateTime.now()),
                review(2, 1, ReviewRating.GOOD, "EXACT", 1, null, "s1", LocalDateTime.now())
        );

        assertEquals(0.5, StatsService.totalActiveMinutes(reviews), 0.0001);
    }

    @Test
    void masteredCardsPerHourUsesProvidedMasteryCountAndActiveTime() {
        // 30 minutes of active (response-time-based) training, not wall-clock time.
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 1, 1_800_000, "s1", LocalDateTime.now())
        );

        LearningEfficiencyStats stats = StatsService.buildLearningEfficiencyStats(reviews, Map.of(), 2);

        assertTrue(stats.hasTrainingTimeSignal());
        assertEquals(0.5, stats.activeReviewHours(), 0.0001);
        assertEquals(4.0, stats.masteredCardsPerHour(), 0.0001);
    }

    @Test
    void insufficientTrainingTimeDoesNotReportMisleadingRates() {
        // One second of active time is nowhere near enough to divide by without an implausible rate.
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 1, 1_000, "s1", LocalDateTime.now())
        );

        LearningEfficiencyStats stats = StatsService.buildLearningEfficiencyStats(reviews, Map.of(), 5);

        assertFalse(stats.hasTrainingTimeSignal());
        assertEquals(0.0, stats.masteredCardsPerHour());
        assertEquals(0.0, stats.objectiveRecallsPerMinute());
    }

    @Test
    void noReviewsReportsNoSignalRatherThanZero() {
        LearningEfficiencyStats stats = StatsService.buildLearningEfficiencyStats(List.of(), Map.of(), 0);

        assertFalse(stats.hasReviewSignal());
        assertFalse(stats.hasTrainingTimeSignal());
        assertFalse(stats.hasSessionSignal());
        assertFalse(stats.hasConfidenceSignal());
        assertFalse(stats.hasSuspendedCardSignal());
        assertFalse(stats.retentionByInterval().sevenToThirteenDays().hasSignal());
    }

    @Test
    void objectiveRecallsPerMinuteOnlyCountsObjectivelyCorrectNonSubjectiveAttempts() {
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 1, 60_000, "s1", LocalDateTime.now()),      // correct recall
                review(2, 1, ReviewRating.AGAIN, "DIFFERENT", 1, 60_000, "s1", LocalDateTime.now()), // incorrect, still counts as time
                new ReviewHistory(3, 1, ReviewRating.EASY, 1, 1, LocalDateTime.now(), true, false,
                        "SUBJECTIVE", "self explained", 60_000, false, "s1")                          // subjective: excluded from numerator
        );

        LearningEfficiencyStats stats = StatsService.buildLearningEfficiencyStats(reviews, Map.of(), 0);

        assertTrue(stats.hasTrainingTimeSignal());
        assertEquals(1, stats.objectiveRecallCount());
        assertEquals(1.0 / 3.0, stats.objectiveRecallsPerMinute(), 0.0001);
    }

    @Test
    void recoveredMissCountsDistinctCardsNotRepeatedAttempts() {
        LocalDateTime t0 = LocalDateTime.now().minusMinutes(10);
        List<ReviewHistory> reviews = List.of(
                review(1, 100, ReviewRating.AGAIN, "DIFFERENT", 1, 1000, "session-a", t0),
                review(2, 200, ReviewRating.AGAIN, "DIFFERENT", 1, 1000, "session-a", t0.plusSeconds(1)),
                review(3, 100, ReviewRating.GOOD, "EXACT", 1, 1000, "session-a", t0.plusSeconds(2)),  // card 100 recovered
                review(4, 100, ReviewRating.GOOD, "EXACT", 1, 1000, "session-a", t0.plusSeconds(3))    // repeat success: no double count
        );

        StatsService.RecoveredMissResult result = StatsService.computeRecoveredMisses(reviews);

        assertEquals(1, result.sessionCount());
        assertEquals(1, result.recoveredCount()); // card 200's miss is never recovered
    }

    @Test
    void recoveredMissesIgnoreReviewsWithoutASessionId() {
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.AGAIN, "DIFFERENT", 1, 1000, null, LocalDateTime.now())
        );

        StatsService.RecoveredMissResult result = StatsService.computeRecoveredMisses(reviews);

        assertEquals(0, result.sessionCount());
        assertEquals(0, result.recoveredCount());
    }

    @Test
    void retentionBucketsGroupReviewsByGapSincePreviousAttempt() {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 7, 1000, "s1", now),        // 7-13 day bucket, retained
                review(2, 2, ReviewRating.AGAIN, "DIFFERENT", 10, 1000, "s1", now),  // 7-13 day bucket, missed
                review(3, 3, ReviewRating.GOOD, "EXACT", 14, 1000, "s1", now),       // 14-29 day bucket, retained
                review(4, 4, ReviewRating.GOOD, "EXACT", 30, 1000, "s1", now),       // 30+ day bucket, retained
                review(5, 5, ReviewRating.GOOD, "EXACT", 3, 1000, "s1", now)         // below 7 days: excluded entirely
        );

        LearningEfficiencyStats.RetentionByInterval retention = StatsService.computeRetentionByInterval(reviews);

        assertEquals(2, retention.sevenToThirteenDays().sampleSize());
        assertEquals(50.0, retention.sevenToThirteenDays().retentionPercent());
        assertEquals(1, retention.fourteenToTwentyNineDays().sampleSize());
        assertEquals(100.0, retention.fourteenToTwentyNineDays().retentionPercent());
        assertEquals(1, retention.thirtyPlusDays().sampleSize());
        assertEquals(100.0, retention.thirtyPlusDays().retentionPercent());
    }

    @Test
    void retentionBucketWithNoSampleReportsNoSignalInsteadOfZero() {
        LearningEfficiencyStats.RetentionByInterval retention = StatsService.computeRetentionByInterval(List.of());

        assertFalse(retention.sevenToThirteenDays().hasSignal());
        assertFalse(retention.fourteenToTwentyNineDays().hasSignal());
        assertFalse(retention.thirtyPlusDays().hasSignal());
    }

    @Test
    void activeMinutesAreGroupedBySkillAndByCardType() {
        Map<Long, Flashcard> cardsById = Map.of(
                1L, flashcard(1, "SQL", CardType.SQL_QUERY),
                2L, flashcard(2, "Git", CardType.GIT_COMMAND)
        );
        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.GOOD, "EXACT", 1, 60_000, "s1", LocalDateTime.now()),
                review(2, 2, ReviewRating.GOOD, "EXACT", 1, 120_000, "s1", LocalDateTime.now())
        );

        Map<String, Double> bySkill = StatsService.activeMinutesBySkill(reviews, cardsById);
        Map<CardType, Double> byCardType = StatsService.activeMinutesByCardType(reviews, cardsById);

        assertEquals(1.0, bySkill.get("SQL"), 0.0001);
        assertEquals(2.0, bySkill.get("Git"), 0.0001);
        assertEquals(1.0, byCardType.get(CardType.SQL_QUERY), 0.0001);
        assertEquals(2.0, byCardType.get(CardType.GIT_COMMAND), 0.0001);
    }

    @Test
    void suspendedCardTimeOnlyCountsCardsCurrentlyInSuspendedState() {
        Flashcard suspendedCard = flashcard(1, "General", CardType.RECALL);
        suspendedCard.setCardState(CardState.SUSPENDED);
        Flashcard activeCard = flashcard(2, "General", CardType.RECALL);
        Map<Long, Flashcard> cardsById = Map.of(1L, suspendedCard, 2L, activeCard);

        List<ReviewHistory> reviews = List.of(
                review(1, 1, ReviewRating.AGAIN, "DIFFERENT", 1, 90_000, "s1", LocalDateTime.now()),
                review(2, 2, ReviewRating.GOOD, "EXACT", 1, 60_000, "s1", LocalDateTime.now())
        );

        StatsService.SuspendedCardTime result = StatsService.computeSuspendedCardTime(reviews, cardsById);

        assertEquals(1, result.cardCount());
        assertEquals(1.5, result.activeMinutes(), 0.0001);
    }

    @Test
    void learningEfficiencyStatsReuseConfidenceCalibrationCalculation() {
        List<ReviewHistory> reviews = List.of(
                objectiveWithConfidence("EXACT", "HIGH"),
                objectiveWithConfidence("DIFFERENT", "LOW")
        );

        LearningEfficiencyStats stats = StatsService.buildLearningEfficiencyStats(reviews, Map.of(), 0);

        assertTrue(stats.hasConfidenceSignal());
        assertEquals(100.0, stats.confidenceCalibrationPercent());
        assertEquals(2, stats.confidenceSampleCount());
    }

    private Flashcard cardInState(long id, CardState state) {
        Flashcard flashcard = new Flashcard(1, "front " + id, "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(id);
        flashcard.setCardState(state);
        return flashcard;
    }

    @Test
    void graduatedCardsAreCountedSeparatelyFromNormallyLearnedCards() {
        List<Flashcard> cards = List.of(
                cardInState(1, CardState.GRADUATED),
                cardInState(2, CardState.GRADUATED),
                cardInState(3, CardState.MASTERED),
                cardInState(4, CardState.REVIEW),
                cardInState(5, CardState.NEW)
        );

        StatsService.CardStateBreakdown breakdown = StatsService.summarizeCardStates(cards);

        assertEquals(2, breakdown.graduatedCards());
        assertEquals(0, breakdown.suspendedCards());
    }

    @Test
    void suspendedCardsAreCountedSeparatelyFromGraduatedCards() {
        List<Flashcard> cards = List.of(
                cardInState(1, CardState.SUSPENDED),
                cardInState(2, CardState.GRADUATED)
        );

        StatsService.CardStateBreakdown breakdown = StatsService.summarizeCardStates(cards);

        assertEquals(1, breakdown.graduatedCards());
        assertEquals(1, breakdown.suspendedCards());
    }

    @Test
    void noGraduatedOrSuspendedCardsGivesZeroForBoth() {
        List<Flashcard> cards = List.of(cardInState(1, CardState.NEW), cardInState(2, CardState.MASTERED));

        StatsService.CardStateBreakdown breakdown = StatsService.summarizeCardStates(cards);

        assertEquals(0, breakdown.graduatedCards());
        assertEquals(0, breakdown.suspendedCards());
    }
}
