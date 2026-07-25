package com.codefit.service;

import com.codefit.model.AssessmentVariant;
import com.codefit.model.CardType;
import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexMatchMode;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assessment grading must dispatch to the exact same validators normal review uses per
 * {@link CardType}, not a second grading engine (#104): {@link SqlCardValidator} for SQL_QUERY,
 * {@link RegexCardValidator} for REGEX_PATTERN, {@link AnswerValidator} for text-matched types, and
 * a self-graded outcome for CONCEPT.
 */
class AssessmentGradingServiceTest {

    private AssessmentVariant variant(String acceptedAnswers) {
        return new AssessmentVariant(1, 0, "scenario", acceptedAnswers, "reference answer", null, null);
    }

    @Test
    void blankAttemptIsEmptyRegardlessOfCardType() {
        AssessmentGradingService.GradingResult result = AssessmentGradingService.grade(
                CardType.RECALL, ValidationMode.CASE_INSENSITIVE, variant("expected"), "   ");
        assertEquals(AssessmentGradingService.Outcome.EMPTY, result.outcome());
    }

    @Test
    void recallMatchingAnAcceptedAnswerIsGradedCorrect() {
        AssessmentGradingService.GradingResult result = AssessmentGradingService.grade(
                CardType.RECALL, ValidationMode.CASE_INSENSITIVE, variant("Optimistic Locking"), "optimistic locking");
        assertTrue(result.isCorrect());
    }

    @Test
    void recallNotMatchingAnAcceptedAnswerIsGradedIncorrect() {
        AssessmentGradingService.GradingResult result = AssessmentGradingService.grade(
                CardType.RECALL, ValidationMode.CASE_INSENSITIVE, variant("Optimistic Locking"), "pessimistic locking");
        assertEquals(AssessmentGradingService.Outcome.INCORRECT, result.outcome());
    }

    @Test
    void conceptScenariosAlwaysNeedSelfRatingRegardlessOfWording() {
        AssessmentGradingService.GradingResult result = AssessmentGradingService.grade(
                CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE, variant(null),
                "a completely different explanation in my own words");
        assertTrue(result.needsSelfRating());
    }

    @Test
    void sqlQueryDispatchesToSqlCardValidatorAgainstAnIsolatedFixture() {
        SqlCardSpec spec = new SqlCardSpec(
                "CREATE TABLE t (id INTEGER PRIMARY KEY, n INTEGER NOT NULL);",
                "INSERT INTO t (id, n) VALUES (1, 5), (2, 9);",
                "SELECT id FROM t WHERE n > 5;",
                null, false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);
        AssessmentVariant sqlVariant = variant(SqlCardSpecCodec.encode(spec));

        AssessmentGradingService.GradingResult correct = AssessmentGradingService.grade(
                CardType.SQL_QUERY, ValidationMode.NORMALIZED_SPACING, sqlVariant, "SELECT id FROM t WHERE n > 5;");
        AssessmentGradingService.GradingResult wrong = AssessmentGradingService.grade(
                CardType.SQL_QUERY, ValidationMode.NORMALIZED_SPACING, sqlVariant, "SELECT id FROM t;");

        assertTrue(correct.isCorrect());
        assertEquals(AssessmentGradingService.Outcome.INCORRECT, wrong.outcome());
    }

    @Test
    void regexPatternDispatchesToRegexCardValidator() {
        RegexCardConfig config = new RegexCardConfig(List.of("555-1234"), List.of("abc"), Set.of(), RegexMatchMode.FIND);
        AssessmentVariant regexVariant = variant(RegexCardCodec.encode(config));

        AssessmentGradingService.GradingResult correct = AssessmentGradingService.grade(
                CardType.REGEX_PATTERN, ValidationMode.CASE_INSENSITIVE, regexVariant, "\\d{3}-\\d{4}");
        AssessmentGradingService.GradingResult wrong = AssessmentGradingService.grade(
                CardType.REGEX_PATTERN, ValidationMode.CASE_INSENSITIVE, regexVariant, "abc");

        assertTrue(correct.isCorrect());
        assertEquals(AssessmentGradingService.Outcome.INCORRECT, wrong.outcome());
    }
}
