package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ReviewRating;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatingGuardrailTest {

    @Test
    void exactUnassistedAnswerAllowsOnlyGoodOrEasy() {
        assertEquals(Set.of(ReviewRating.GOOD, ReviewRating.EASY),
                RatingGuardrail.allowedRatings(CardType.RECALL, "EXACT", false));
        assertEquals(Set.of(ReviewRating.GOOD, ReviewRating.EASY),
                RatingGuardrail.allowedRatings(CardType.RECALL, "CLOSE_SPACING", false));
    }

    @Test
    void correctWithHintCapsAtGood() {
        Set<ReviewRating> allowed = RatingGuardrail.allowedRatings(CardType.RECALL, "EXACT", true);
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD, ReviewRating.GOOD), allowed);
        assertFalse(allowed.contains(ReviewRating.EASY));
    }

    @Test
    void correctAfterTimeoutCapsAtHard() {
        Set<ReviewRating> allowed = RatingGuardrail.allowedRatings(CardType.RECALL, "TIMED_OUT_WITH_ATTEMPT", false);
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD), allowed);
    }

    @Test
    void incorrectOrEmptyOrPlainTimeoutAllowsOnlyAgainOrHard() {
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD),
                RatingGuardrail.allowedRatings(CardType.RECALL, "DIFFERENT", false));
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD),
                RatingGuardrail.allowedRatings(CardType.RECALL, "EMPTY", false));
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD),
                RatingGuardrail.allowedRatings(CardType.RECALL, "TIMED_OUT", false));
        assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD),
                RatingGuardrail.allowedRatings(CardType.RECALL, null, false));
    }

    @Test
    void everyObjectivelyGradedCardTypeIsRestricted() {
        for (CardType cardType : CardType.values()) {
            if (cardType == CardType.CONCEPT) {
                continue;
            }
            assertTrue(RatingGuardrail.isObjectivelyGraded(cardType), cardType + " should be objectively graded");
            assertEquals(Set.of(ReviewRating.AGAIN, ReviewRating.HARD),
                    RatingGuardrail.allowedRatings(cardType, "DIFFERENT", false));
        }
    }

    @Test
    void subjectiveConceptCardsAreNeverRestricted() {
        assertFalse(RatingGuardrail.isObjectivelyGraded(CardType.CONCEPT));
        assertEquals(Set.of(ReviewRating.values()),
                RatingGuardrail.allowedRatings(CardType.CONCEPT, "DIFFERENT", false));
        assertEquals(Set.of(ReviewRating.values()),
                RatingGuardrail.allowedRatings(CardType.CONCEPT, "EXACT", true));
    }

    @Test
    void blockedReasonExplainsWhyEasyIsUnavailableAfterHint() {
        String reason = RatingGuardrail.blockedReason(ReviewRating.EASY, CardType.RECALL, "EXACT", true);
        assertTrue(reason.contains("hint"));
    }

    @Test
    void blockedReasonExplainsWhyEasyIsUnavailableAfterIncorrectAnswer() {
        String reason = RatingGuardrail.blockedReason(ReviewRating.EASY, CardType.RECALL, "DIFFERENT", false);
        assertTrue(reason.toLowerCase().contains("incorrect") || reason.toLowerCase().contains("empty"));
    }

    @Test
    void canGraduateOnlyOnCorrectUnassistedTimelyAnswer() {
        assertTrue(RatingGuardrail.canGraduate(CardType.RECALL, "EXACT", false, true));
        assertTrue(RatingGuardrail.canGraduate(CardType.RECALL, "CLOSE_SPACING", false, true));
    }

    @Test
    void cannotGraduateAHintedAnswer() {
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, "EXACT", true, true));
    }

    @Test
    void cannotGraduateAnIncorrectOrEmptyAnswer() {
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, "DIFFERENT", false, true));
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, "EMPTY", false, true));
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, null, false, true));
    }

    @Test
    void cannotGraduateATimedOutAnswerEvenIfCorrect() {
        // submittedInTime=false covers a plain timeout; TIMED_OUT_WITH_ATTEMPT covers a correct
        // attempt recorded after time expired. Both must be blocked.
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, "EXACT", false, false));
        assertFalse(RatingGuardrail.canGraduate(CardType.RECALL, "TIMED_OUT_WITH_ATTEMPT", false, true));
    }

    @Test
    void cannotGraduateASelfGradedConceptCard() {
        assertFalse(RatingGuardrail.canGraduate(CardType.CONCEPT, "EXACT", false, true));
        String reason = RatingGuardrail.graduationBlockedReason(CardType.CONCEPT, "EXACT", false, true);
        assertTrue(reason.toLowerCase().contains("self-graded"));
    }

    @Test
    void graduationBlockedReasonNamesTheSpecificBlocker() {
        assertTrue(RatingGuardrail.graduationBlockedReason(CardType.RECALL, "EXACT", true, true).toLowerCase().contains("hint"));
        assertTrue(RatingGuardrail.graduationBlockedReason(CardType.RECALL, "EXACT", false, false).toLowerCase().contains("time"));
        assertTrue(RatingGuardrail.graduationBlockedReason(CardType.RECALL, "DIFFERENT", false, true).toLowerCase().contains("incorrect"));
    }
}
