package com.codefit.service;

import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexCardFlag;
import com.codefit.model.RegexMatchMode;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexCardValidatorTest {

    private static RegexCardConfig config(List<String> mustMatch, List<String> mustNotMatch,
                                          Set<RegexCardFlag> flags, RegexMatchMode matchMode) {
        return new RegexCardConfig(mustMatch, mustNotMatch, flags, matchMode);
    }

    @Test
    void equivalentlyWrittenPatternsAreBothAccepted() {
        RegexCardConfig phoneNumber = config(List.of("555-1234", "555-0000"), List.of("abc", "555-12345"),
                Set.of(), RegexMatchMode.FULL_MATCH);

        assertTrue(RegexCardValidator.grade("[0-9]{3}-[0-9]{4}", phoneNumber).passed());
        assertTrue(RegexCardValidator.grade("\\d{3}-\\d{4}", phoneNumber).passed());
        assertTrue(RegexCardValidator.grade("\\d\\d\\d-\\d\\d\\d\\d", phoneNumber).passed());
    }

    @Test
    void patternResemblingTheAnswerButMatchingADifferentLanguageIsRejected() {
        // Same length and shape as a real phone number but accepts any character instead of only
        // digits, so it must fail against the negative example "abc-defg" — text-similarity to the
        // intended \d{3}-\d{4} pattern is not enough, actual character-class behavior is graded.
        RegexCardConfig phoneNumber = config(List.of("555-1234"), List.of("abc-defg"), Set.of(), RegexMatchMode.FULL_MATCH);
        RegexCardValidator.Result result = RegexCardValidator.grade(".{3}-.{4}", phoneNumber);
        assertFalse(result.passed());
        assertEquals(RegexCardValidator.Outcome.FAIL, result.outcome());
        assertEquals("abc-defg", result.failingExample());
        assertFalse(result.failingExampleShouldMatch());
    }

    @Test
    void anchorsAreRespectedUnderFindMode() {
        // Under FIND, an unanchored "hello" is satisfied by finding it anywhere in the string, so it
        // wrongly matches the negative examples too; only the anchored pattern rejects them.
        RegexCardConfig config = config(List.of("hello"), List.of("hello world", "say hello"),
                Set.of(), RegexMatchMode.FIND);

        assertTrue(RegexCardValidator.grade("^hello$", config).passed());
        assertFalse(RegexCardValidator.grade("hello", config).passed());
    }

    @Test
    void alternationMatchesEitherBranch() {
        RegexCardConfig config = config(List.of("cat", "dog"), List.of("bird"), Set.of(), RegexMatchMode.FULL_MATCH);
        assertTrue(RegexCardValidator.grade("cat|dog", config).passed());

        RegexCardValidator.Result missingBranch = RegexCardValidator.grade("cat", config);
        assertFalse(missingBranch.passed());
        assertEquals("dog", missingBranch.failingExample());
    }

    @Test
    void caseInsensitiveFlagIsHonoredWhenConfigured() {
        RegexCardConfig config = config(List.of("HELLO", "hello"), List.of("goodbye"),
                Set.of(RegexCardFlag.CASE_INSENSITIVE), RegexMatchMode.FULL_MATCH);
        assertTrue(RegexCardValidator.grade("hello", config).passed());

        RegexCardConfig caseSensitive = config(List.of("HELLO", "hello"), List.of("goodbye"), Set.of(), RegexMatchMode.FULL_MATCH);
        assertFalse(RegexCardValidator.grade("hello", caseSensitive).passed());
    }

    @Test
    void fullMatchModeRequiresConsumingTheEntireExample() {
        RegexCardConfig fullMatchMode = config(List.of("abcd"), List.of(), Set.of(), RegexMatchMode.FULL_MATCH);
        assertFalse(RegexCardValidator.grade("bc", fullMatchMode).passed());

        RegexCardConfig findMode = config(List.of("abcd"), List.of(), Set.of(), RegexMatchMode.FIND);
        assertTrue(RegexCardValidator.grade("bc", findMode).passed());
    }

    @Test
    void invalidSyntaxIsCaughtWithoutThrowing() {
        RegexCardConfig config = config(List.of("abc"), List.of(), Set.of(), RegexMatchMode.FIND);
        RegexCardValidator.Result result = RegexCardValidator.grade("[abc", config);
        assertEquals(RegexCardValidator.Outcome.INVALID_SYNTAX, result.outcome());
        assertFalse(result.passed());
    }

    @Test
    @Timeout(5)
    void catastrophicBacktrackingIsCaughtByTheTimeoutInsteadOfHanging() {
        // Modern JDK regex engines special-case simple repeated-group forms like (a+)+$ (they detect
        // a repetition making no forward progress and stop backtracking), so that textbook example no
        // longer reproduces exponential blowup here. Russ Cox's classic construction — n optional a's
        // followed by n required a's, matched against n a's — forces 2^n backtracking paths regardless,
        // since it is a flat chain of distinct "a?" nodes rather than one repeated group: empirically
        // n=26 alone already takes over a second on this JVM (single-threaded, no timeout).
        int n = 26;
        String pathologicalPattern = "a?".repeat(n) + "a".repeat(n);
        RegexCardConfig config = config(List.of("a".repeat(n)), List.of(), Set.of(), RegexMatchMode.FULL_MATCH);

        RegexCardValidator.Result result = RegexCardValidator.grade(pathologicalPattern, config);
        assertEquals(RegexCardValidator.Outcome.TIMEOUT, result.outcome());
        assertFalse(result.passed());
    }

    @Test
    void misconfiguredCardWithNoExamplesFailsSafely() {
        RegexCardConfig empty = config(List.of(), List.of(), Set.of(), RegexMatchMode.FIND);
        assertEquals(RegexCardValidator.Outcome.MISCONFIGURED, RegexCardValidator.grade("anything", empty).outcome());
    }

    @Test
    void blankSubmissionFailsWithoutCompiling() {
        RegexCardConfig config = config(List.of("abc"), List.of(), Set.of(), RegexMatchMode.FIND);
        assertEquals(RegexCardValidator.Outcome.FAIL, RegexCardValidator.grade("", config).outcome());
        assertEquals(RegexCardValidator.Outcome.FAIL, RegexCardValidator.grade(null, config).outcome());
    }

    @Test
    void matchesConvenienceMethodDecodesEncodedConfig() {
        RegexCardConfig config = config(List.of("555-1234"), List.of("abc"), Set.of(), RegexMatchMode.FULL_MATCH);
        String encoded = RegexCardCodec.encode(config);
        assertTrue(RegexCardValidator.matches("\\d{3}-\\d{4}", encoded));
        assertFalse(RegexCardValidator.matches(".*", encoded));
    }

    @Test
    void answerValidatorDispatchesRegexExamplesMode() {
        RegexCardConfig config = config(List.of("555-1234"), List.of("abc"), Set.of(), RegexMatchMode.FULL_MATCH);
        String encoded = RegexCardCodec.encode(config);
        assertTrue(AnswerValidator.matches("\\d{3}-\\d{4}", encoded, ValidationMode.REGEX_EXAMPLES));
        assertFalse(AnswerValidator.matches(".*", encoded, ValidationMode.REGEX_EXAMPLES));
    }
}
