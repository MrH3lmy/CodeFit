package com.codefit.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewHistoryTest {

    private ReviewHistory history(ReviewRating rating, boolean submittedInTime, String validationResult) {
        return new ReviewHistory(1, 1, rating, 0, 1, LocalDateTime.now(), submittedInTime, false,
                validationResult, "attempt", 1500, false, "session-1");
    }

    @Test
    void exactMatchIsObjectivelyCorrectRegardlessOfRating() {
        assertTrue(history(ReviewRating.HARD, true, "EXACT").isObjectivelyCorrect());
        assertTrue(history(ReviewRating.EASY, true, "CLOSE_SPACING").isObjectivelyCorrect());
    }

    @Test
    void wrongOrEmptyAttemptIsNeverObjectivelyCorrectEvenIfRatedEasy() {
        assertFalse(history(ReviewRating.EASY, true, "DIFFERENT").isObjectivelyCorrect());
        assertFalse(history(ReviewRating.EASY, true, "EMPTY").isObjectivelyCorrect());
        assertFalse(history(ReviewRating.EASY, false, "TIMED_OUT").isObjectivelyCorrect());
    }

    @Test
    void lateButCorrectAttemptIsObjectivelyCorrectButNotTimedSuccess() {
        ReviewHistory lateCorrect = history(ReviewRating.HARD, false, "TIMED_OUT_WITH_ATTEMPT");
        assertTrue(lateCorrect.isObjectivelyCorrect());
        assertFalse(lateCorrect.isTimedSuccess());
    }

    @Test
    void timedSuccessRequiresBothInTimeAndCorrect() {
        assertTrue(history(ReviewRating.GOOD, true, "EXACT").isTimedSuccess());
        assertFalse(history(ReviewRating.GOOD, false, "EXACT").isTimedSuccess());
        assertFalse(history(ReviewRating.GOOD, true, "DIFFERENT").isTimedSuccess());
    }

    @Test
    void legacyRowsWithoutValidationResultFallBackToRating() {
        assertTrue(history(ReviewRating.GOOD, true, null).isObjectivelyCorrect());
        assertTrue(history(ReviewRating.EASY, true, "").isObjectivelyCorrect());
        assertFalse(history(ReviewRating.HARD, true, null).isObjectivelyCorrect());
        assertFalse(history(ReviewRating.AGAIN, true, null).isObjectivelyCorrect());
    }

    @Test
    void subjectiveValidationResultIsNeitherCorrectNorIncorrect() {
        ReviewHistory subjective = history(ReviewRating.GOOD, true, "SUBJECTIVE");
        assertTrue(subjective.isSubjective());
        assertFalse(subjective.isObjectivelyCorrect());
        assertFalse(subjective.isTimedSuccess());
    }

    @Test
    void nonSubjectiveValidationResultsAreNotFlaggedSubjective() {
        assertFalse(history(ReviewRating.GOOD, true, "EXACT").isSubjective());
        assertFalse(history(ReviewRating.GOOD, true, "DIFFERENT").isSubjective());
        assertFalse(history(ReviewRating.GOOD, true, null).isSubjective());
    }

    @Test
    void confidenceIsStoredIndependentlyAndNeverInferredFromRating() {
        // High confidence but an objectively incorrect answer: confidence must be exactly what
        // was recorded, not derived from (or overloaded onto) the scheduler rating.
        ReviewHistory highConfidenceWrong = new ReviewHistory(1, 1, ReviewRating.AGAIN, 0, 0,
                LocalDateTime.now(), true, false, "DIFFERENT", "attempt", 1000, false, "session", "HIGH");
        assertEquals("HIGH", highConfidenceWrong.getConfidence());
        assertFalse(highConfidenceWrong.isObjectivelyCorrect());

        // Low confidence but objectively correct: again, confidence is independent of correctness.
        ReviewHistory lowConfidenceCorrect = new ReviewHistory(1, 1, ReviewRating.GOOD, 0, 1,
                LocalDateTime.now(), true, false, "EXACT", "attempt", 1000, false, "session", "LOW");
        assertEquals("LOW", lowConfidenceCorrect.getConfidence());
        assertTrue(lowConfidenceCorrect.isObjectivelyCorrect());
    }

    @Test
    void confidenceDefaultsToNullWhenNotProvided() {
        assertNull(history(ReviewRating.GOOD, true, "EXACT").getConfidence());
    }
}
