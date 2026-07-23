package com.codefit.service;

import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsServiceTest {

    private ReviewHistory objective(ReviewRating rating, String validationResult, boolean submittedInTime) {
        return new ReviewHistory(0, 1, rating, 0, 1, LocalDateTime.now(), submittedInTime, false,
                validationResult, "attempt", 1000, false, "session");
    }

    private ReviewHistory subjective(ReviewRating rating) {
        return new ReviewHistory(0, 2, rating, 0, 30, LocalDateTime.now(), true, false,
                "SUBJECTIVE", "my own explanation in different words", 4000, false, "session");
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
}
