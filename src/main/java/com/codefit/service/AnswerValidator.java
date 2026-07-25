package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;

import java.util.List;

/**
 * Compares a learner's submitted attempt against a card's accepted answers. Extracted from the
 * review UI so the matching rules for every {@link ValidationMode} can be unit tested directly.
 */
public final class AnswerValidator {

    public enum Outcome {
        EMPTY, EXACT, CLOSE_SPACING, DIFFERENT, SUBJECTIVE, JAVA_PENDING
    }

    private AnswerValidator() {
    }

    /**
     * Concept/reflection cards ({@link CardType#CONCEPT}) are never text-matched against an
     * answer key — a correct explanation in different wording must not be graded DIFFERENT.
     * They resolve to {@link Outcome#SUBJECTIVE} once any attempt is entered, leaving grading to
     * the learner's own self-rating. Every other card type is graded objectively as before.
     */
    public static Outcome validateForCardType(CardType cardType, String attempt, List<String> acceptedAnswers,
                                              ValidationMode validationMode) {
        if (cardType == CardType.CONCEPT) {
            String trimmedAttempt = attempt == null ? "" : attempt.strip();
            return trimmedAttempt.isEmpty() ? Outcome.EMPTY : Outcome.SUBJECTIVE;
        }
        if (cardType == CardType.JAVA_CODE) {
            // Grading a Java attempt means compiling and running it in a subprocess, which is far
            // too slow to do on every keystroke. JAVA_PENDING just unlocks "Show Answer"; the actual
            // sandbox run happens once, explicitly, when the learner reveals the answer.
            String trimmedAttempt = attempt == null ? "" : attempt.strip();
            return trimmedAttempt.isEmpty() ? Outcome.EMPTY : Outcome.JAVA_PENDING;
        }
        return validate(attempt, acceptedAnswers, validationMode);
    }

    public static Outcome validate(String attempt, List<String> acceptedAnswers, ValidationMode validationMode) {
        String trimmedAttempt = attempt == null ? "" : attempt.strip();
        if (trimmedAttempt.isEmpty()) {
            return Outcome.EMPTY;
        }
        if (acceptedAnswers != null) {
            for (String expectedAnswer : acceptedAnswers) {
                if (matches(trimmedAttempt, expectedAnswer, validationMode)) {
                    return Outcome.EXACT;
                }
                if (normalizeSpacing(trimmedAttempt).equalsIgnoreCase(normalizeSpacing(expectedAnswer))) {
                    return Outcome.CLOSE_SPACING;
                }
            }
        }
        return Outcome.DIFFERENT;
    }

    public static boolean matches(String attempt, String expectedAnswer, ValidationMode validationMode) {
        return switch (validationMode == null ? ValidationMode.CASE_INSENSITIVE : validationMode) {
            case EXACT -> attempt.equals(expectedAnswer);
            case CASE_INSENSITIVE -> attempt.equalsIgnoreCase(expectedAnswer);
            case NORMALIZED_SPACING -> normalizeSpacing(attempt).equalsIgnoreCase(normalizeSpacing(expectedAnswer));
            case COMMAND_NORMALIZED -> CommandValidator.matches(attempt, expectedAnswer);
            case REGEX_EXAMPLES -> RegexCardValidator.matches(attempt, expectedAnswer);
        };
    }

    public static String normalizeSpacing(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    public static String normalizeCommand(String value) {
        return normalizeSpacing(value).replaceAll("\\s*=\\s*", "=");
    }
}
