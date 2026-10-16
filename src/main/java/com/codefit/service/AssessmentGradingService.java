package com.codefit.service;

import com.codefit.model.AssessmentVariant;
import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;

/**
 * Grades a transfer-assessment attempt using the exact same per-{@link CardType} validators normal
 * review already relies on ({@link SqlCardValidator}, {@link RegexCardValidator}, {@link
 * AnswerValidator}), rather than a second grading engine. An assessment item only ever needs its
 * own storage/selection semantics (see {@code WeeklyAssessmentSelectionService}), not new grading
 * logic (#104).
 */
public final class AssessmentGradingService {

    private AssessmentGradingService() {}

    public enum Outcome {
        EMPTY,
        CORRECT,
        INCORRECT,
        SUBJECTIVE
    }

    public record GradingResult(
            Outcome outcome, String feedback, String validator, boolean objective) {
        /** Callers without captured grading facts retain unknown provenance. */
        public GradingResult(Outcome outcome, String feedback) {
            this(outcome, feedback, "UNKNOWN", false);
        }

        public boolean isCorrect() {
            return outcome == Outcome.CORRECT;
        }

        public boolean needsSelfRating() {
            return outcome == Outcome.SUBJECTIVE;
        }
    }

    public static GradingResult grade(
            CardType cardType,
            ValidationMode validationMode,
            AssessmentVariant variant,
            String attempt) {
        String trimmedAttempt = attempt == null ? "" : attempt.strip();
        if (trimmedAttempt.isEmpty()) {
            return new GradingResult(Outcome.EMPTY, "");
        }

        if (cardType == CardType.SQL_QUERY) {
            SqlCardValidator.GradingResult sqlResult =
                    SqlCardValidator.grade(trimmedAttempt, variant.acceptedAnswers());
            if (sqlResult.outcome() == SqlCardValidator.Outcome.EMPTY) {
                return new GradingResult(Outcome.EMPTY, "");
            }
            return new GradingResult(
                    sqlResult.isCorrect() ? Outcome.CORRECT : Outcome.INCORRECT,
                    sqlResult.feedback(),
                    "SqlCardValidator",
                    sqlResult.outcome() == SqlCardValidator.Outcome.CORRECT
                            || sqlResult.outcome() == SqlCardValidator.Outcome.WRONG_RESULT);
        }
        if (cardType == CardType.REGEX_PATTERN) {
            RegexCardValidator.Result regexResult =
                    RegexCardValidator.grade(
                            trimmedAttempt, RegexCardCodec.decode(variant.acceptedAnswers()));
            return new GradingResult(
                    regexResult.passed() ? Outcome.CORRECT : Outcome.INCORRECT,
                    describeRegexResult(regexResult),
                    "RegexCardValidator",
                    regexResult.outcome() == RegexCardValidator.Outcome.PASS
                            || regexResult.outcome() == RegexCardValidator.Outcome.FAIL
                            || regexResult.outcome() == RegexCardValidator.Outcome.INVALID_SYNTAX);
        }

        AnswerValidator.Outcome outcome =
                AnswerValidator.validateForCardType(
                        cardType,
                        trimmedAttempt,
                        AcceptedAnswerCodec.decode(variant.acceptedAnswers()),
                        validationMode);
        return switch (outcome) {
            case EMPTY -> new GradingResult(Outcome.EMPTY, "");
            case EXACT ->
                    new GradingResult(
                            Outcome.CORRECT,
                            "Matches an accepted answer.",
                            "AnswerValidator",
                            true);
            case CLOSE_SPACING ->
                    new GradingResult(
                            Outcome.CORRECT,
                            "Matches an accepted answer (spacing normalized).",
                            "AnswerValidator",
                            true);
            case DIFFERENT ->
                    new GradingResult(
                            Outcome.INCORRECT,
                            "Does not match an accepted answer.",
                            "AnswerValidator",
                            true);
            // CONCEPT (open-ended transfer scenarios) and JAVA_CODE both need a human judgment call
            // rather than a text match, exactly as they do in normal review.
            case SUBJECTIVE, JAVA_PENDING ->
                    new GradingResult(
                            Outcome.SUBJECTIVE,
                            "Compare your answer with the reference answer and self-rate.",
                            "SELF_RATING",
                            false);
        };
    }

    private static String describeRegexResult(RegexCardValidator.Result result) {
        return switch (result.outcome()) {
            case PASS -> "Pattern matches every required example and rejects every disallowed one.";
            case INVALID_SYNTAX ->
                    "Invalid regex syntax"
                            + (result.syntaxError() == null ? "." : ": " + result.syntaxError());
            case TIMEOUT ->
                    "Pattern took too long to evaluate against \""
                            + result.failingExample()
                            + "\".";
            case MISCONFIGURED -> "This item's regex examples are not configured correctly.";
            case FAIL ->
                    result.failingExampleShouldMatch()
                            ? "Pattern should match \""
                                    + result.failingExample()
                                    + "\" but did not."
                            : "Pattern incorrectly matches \"" + result.failingExample() + "\".";
        };
    }
}
