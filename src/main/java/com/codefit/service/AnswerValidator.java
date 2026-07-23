package com.codefit.service;

import com.codefit.model.ValidationMode;

import java.util.List;

/**
 * Compares a learner's submitted attempt against a card's accepted answers. Extracted from the
 * review UI so the matching rules for every {@link ValidationMode} can be unit tested directly.
 */
public final class AnswerValidator {

    public enum Outcome {
        EMPTY, EXACT, CLOSE_SPACING, DIFFERENT
    }

    private AnswerValidator() {
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
            case COMMAND_NORMALIZED -> normalizeCommand(attempt).equalsIgnoreCase(normalizeCommand(expectedAnswer));
        };
    }

    public static String normalizeSpacing(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    public static String normalizeCommand(String value) {
        return normalizeSpacing(value).replaceAll("\\s*=\\s*", "=");
    }
}
