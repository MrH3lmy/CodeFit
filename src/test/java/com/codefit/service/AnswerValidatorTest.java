package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerValidatorTest {

    @Test
    void conceptCardWithAnswerInDifferentWordingIsSubjectiveNotDifferent() {
        // The accepted answer is a canonical explanation, but the learner wrote it in their own
        // words. A CONCEPT card must never be text-matched, so this is SUBJECTIVE, not DIFFERENT
        // even though the wording doesn't match any accepted alternative.
        AnswerValidator.Outcome outcome = AnswerValidator.validateForCardType(CardType.CONCEPT,
                "It separates the API shape from how data is stored in the database",
                List.of("DTOs are API contracts and entities are persistence models"),
                ValidationMode.CASE_INSENSITIVE);

        assertEquals(AnswerValidator.Outcome.SUBJECTIVE, outcome);
    }

    @Test
    void conceptCardWithEmptyAttemptIsStillEmpty() {
        AnswerValidator.Outcome outcome = AnswerValidator.validateForCardType(CardType.CONCEPT, "",
                List.of("any accepted answer"), ValidationMode.CASE_INSENSITIVE);

        assertEquals(AnswerValidator.Outcome.EMPTY, outcome);
    }

    @Test
    void conceptCardIsSubjectiveEvenWhenAttemptExactlyMatchesAcceptedAnswer() {
        // Even a word-for-word match must not be reported as an objective EXACT for a concept
        // card — grading always defers to the learner's self-rating.
        AnswerValidator.Outcome outcome = AnswerValidator.validateForCardType(CardType.CONCEPT,
                "DTOs are API contracts and entities are persistence models",
                List.of("DTOs are API contracts and entities are persistence models"),
                ValidationMode.CASE_INSENSITIVE);

        assertEquals(AnswerValidator.Outcome.SUBJECTIVE, outcome);
    }

    @Test
    void nonConceptCardTypesAreStillTextGradedObjectively() {
        AnswerValidator.Outcome outcome = AnswerValidator.validateForCardType(CardType.RECALL,
                "extends", List.of("extends"), ValidationMode.CASE_INSENSITIVE);

        assertEquals(AnswerValidator.Outcome.EXACT, outcome);
    }

    @Test
    void emptyAttemptIsAlwaysEmpty() {
        assertEquals(AnswerValidator.Outcome.EMPTY,
                AnswerValidator.validate("", List.of("extends"), ValidationMode.CASE_INSENSITIVE));
        assertEquals(AnswerValidator.Outcome.EMPTY,
                AnswerValidator.validate("   ", List.of("extends"), ValidationMode.CASE_INSENSITIVE));
        assertEquals(AnswerValidator.Outcome.EMPTY,
                AnswerValidator.validate(null, List.of("extends"), ValidationMode.CASE_INSENSITIVE));
    }

    @Test
    void exactModeRequiresExactCaseAndSpacing() {
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("extends", List.of("extends"), ValidationMode.EXACT));
        // A case difference still fails strict EXACT matching, but the case-insensitive
        // near-miss check reports it as CLOSE_SPACING rather than a flat DIFFERENT.
        assertEquals(AnswerValidator.Outcome.CLOSE_SPACING,
                AnswerValidator.validate("Extends", List.of("extends"), ValidationMode.EXACT));
        assertEquals(AnswerValidator.Outcome.DIFFERENT,
                AnswerValidator.validate("implements", List.of("extends"), ValidationMode.EXACT));
    }

    @Test
    void caseInsensitiveModeIgnoresCase() {
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("EXTENDS", List.of("extends"), ValidationMode.CASE_INSENSITIVE));
    }

    @Test
    void normalizedSpacingModeCollapsesWhitespace() {
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("SELECT  email   FROM users", List.of("SELECT email FROM users"),
                        ValidationMode.NORMALIZED_SPACING));
    }

    @Test
    void commandNormalizedModeIgnoresSpacingAroundEquals() {
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("java -jar app.jar --spring.profiles.active = prod",
                        List.of("java -jar app.jar --spring.profiles.active=prod"), ValidationMode.COMMAND_NORMALIZED));
    }

    @Test
    void unrelatedAttemptIsDifferentNotCloseSpacing() {
        assertEquals(AnswerValidator.Outcome.DIFFERENT,
                AnswerValidator.validate("implements", List.of("extends"), ValidationMode.CASE_INSENSITIVE));
    }

    @Test
    void exactModeFallsBackToCloseSpacingWhenOnlySpacingDiffers() {
        // EXACT mode demands exact spacing, but if the attempt matches once normalized it is
        // reported as a near-miss instead of a flat DIFFERENT.
        assertEquals(AnswerValidator.Outcome.CLOSE_SPACING,
                AnswerValidator.validate("SELECT  email FROM users", List.of("SELECT email FROM users"), ValidationMode.EXACT));
    }

    @Test
    void matchesAnyAlternativeAmongMultipleAcceptedAnswers() {
        List<String> alternatives = List.of("@ControllerAdvice", "@RestControllerAdvice");
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("@RestControllerAdvice", alternatives, ValidationMode.CASE_INSENSITIVE));
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("@controlleradvice", alternatives, ValidationMode.CASE_INSENSITIVE));
        assertEquals(AnswerValidator.Outcome.DIFFERENT,
                AnswerValidator.validate("@Component", alternatives, ValidationMode.CASE_INSENSITIVE));
    }

    @Test
    void nullValidationModeDefaultsToCaseInsensitive() {
        assertEquals(AnswerValidator.Outcome.EXACT,
                AnswerValidator.validate("EXTENDS", List.of("extends"), null));
    }

    @Test
    void normalizeSpacingCollapsesInternalWhitespaceAndStrips() {
        assertEquals("a b c", AnswerValidator.normalizeSpacing("  a   b\tc  "));
    }

    @Test
    void normalizeCommandRemovesSpacingAroundEquals() {
        assertEquals("--flag=value", AnswerValidator.normalizeCommand("--flag = value"));
    }
}
