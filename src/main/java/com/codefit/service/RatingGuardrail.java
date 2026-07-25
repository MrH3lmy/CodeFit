package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ReviewRating;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decides which scheduler ratings a learner may pick for a revealed card, based on objective
 * validation evidence rather than trusting the self-selected rating alone.
 *
 * <p>Wrong or empty answers, and correct-but-late answers, cap out at Hard; a correct answer
 * helped by a hint caps at Good; only a correct, on-time, unassisted answer may be rated Good or
 * Easy — rating that Again/Hard would misrepresent an objectively correct recall. Subjective
 * concept/reflection cards ({@link CardType#CONCEPT}) are never objectively validated, so
 * self-rating stays unrestricted.
 */
public final class RatingGuardrail {

    private RatingGuardrail() {
    }

    public static boolean isObjectivelyGraded(CardType cardType) {
        return cardType != null && cardType != CardType.CONCEPT;
    }

    /**
     * @param validationResult the recorded objective validation outcome (e.g. "EXACT",
     *                          "CLOSE_SPACING", "DIFFERENT", "TIMED_OUT", "TIMED_OUT_WITH_ATTEMPT",
     *                          "EMPTY"), or null/blank if unknown
     */
    public static Set<ReviewRating> allowedRatings(CardType cardType, String validationResult, boolean hintUsed) {
        if (!isObjectivelyGraded(cardType)) {
            return EnumSet.allOf(ReviewRating.class);
        }
        if (validationResult == null || validationResult.isBlank()) {
            return EnumSet.of(ReviewRating.AGAIN, ReviewRating.HARD);
        }
        return switch (validationResult) {
            // JAVA_CORRECT is the sandboxed-execution equivalent of an EXACT text match: the
            // attempt compiled, ran, and matched the expected output/exception.
            case "EXACT", "CLOSE_SPACING", "JAVA_CORRECT" -> hintUsed
                    ? EnumSet.of(ReviewRating.AGAIN, ReviewRating.HARD, ReviewRating.GOOD)
                    : EnumSet.of(ReviewRating.GOOD, ReviewRating.EASY);
            default -> EnumSet.of(ReviewRating.AGAIN, ReviewRating.HARD);
        };
    }

    public static String blockedReason(ReviewRating rating, CardType cardType, String validationResult, boolean hintUsed) {
        String label = rating.name().charAt(0) + rating.name().substring(1).toLowerCase();
        if (!isObjectivelyGraded(cardType)) {
            return label + " is available.";
        }
        boolean correct = "EXACT".equals(validationResult) || "CLOSE_SPACING".equals(validationResult)
                || "JAVA_CORRECT".equals(validationResult);
        if (correct) {
            return hintUsed
                    ? label + " isn't available because a hint was used; the highest rating for a hinted answer is Good."
                    : label + " isn't available for a correct, unassisted, on-time answer; rate it Good or Easy.";
        }
        if ("TIMED_OUT_WITH_ATTEMPT".equals(validationResult)) {
            return label + " isn't available because time expired before the answer was submitted; the highest rating is Hard.";
        }
        return label + " isn't available for an incorrect or empty answer; rate it Again or Hard.";
    }
}
