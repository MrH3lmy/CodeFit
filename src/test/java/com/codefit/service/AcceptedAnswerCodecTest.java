package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptedAnswerCodecTest {

    @Test
    void decodeReturnsEmptyListForNullOrBlank() {
        assertTrue(AcceptedAnswerCodec.decode(null).isEmpty());
        assertTrue(AcceptedAnswerCodec.decode("").isEmpty());
        assertTrue(AcceptedAnswerCodec.decode("   ").isEmpty());
    }

    @Test
    void decodeReturnsSingleAnswerAsIsForPlainText() {
        assertEquals(List.of("extends"), AcceptedAnswerCodec.decode("extends"));
    }

    @Test
    void decodeSplitsLegacyMultilineTextIntoAlternatives() {
        assertEquals(List.of("first answer", "second answer"),
                AcceptedAnswerCodec.decode("first answer\nsecond answer"));
    }

    @Test
    void decodeParsesJsonArrayAlternatives() {
        assertEquals(List.of("@ControllerAdvice", "@RestControllerAdvice"),
                AcceptedAnswerCodec.decode("[\"@ControllerAdvice\",\"@RestControllerAdvice\"]"));
    }

    @Test
    void decodeHandlesEscapedCharactersInJsonArray() {
        assertEquals(List.of("say \"hi\"", "line1\nline2"),
                AcceptedAnswerCodec.decode("[\"say \\\"hi\\\"\",\"line1\\nline2\"]"));
    }

    @Test
    void decodeDoesNotSplitRegexPatternsContainingPipe() {
        assertEquals(List.of("\\d+|\\w+"), AcceptedAnswerCodec.decode("\\d+|\\w+"));
    }

    @Test
    void decodeFallsBackToPlainTextForMalformedJsonLookingInput() {
        assertEquals(List.of("[not valid json"), AcceptedAnswerCodec.decode("[not valid json"));
        assertEquals(List.of("[oops]"), AcceptedAnswerCodec.decode("[oops]"));
    }

    @Test
    void encodeReturnsEmptyStringForNoAnswers() {
        assertEquals("", AcceptedAnswerCodec.encode(null));
        assertEquals("", AcceptedAnswerCodec.encode(List.of()));
        assertEquals("", AcceptedAnswerCodec.encode(List.of("", "   ")));
    }

    @Test
    void encodeReturnsPlainTextForSingleAnswer() {
        assertEquals("extends", AcceptedAnswerCodec.encode(List.of("extends")));
    }

    @Test
    void encodeReturnsJsonArrayForMultipleAnswers() {
        assertEquals("[\"@ControllerAdvice\",\"@RestControllerAdvice\"]",
                AcceptedAnswerCodec.encode(List.of("@ControllerAdvice", "@RestControllerAdvice")));
    }

    @Test
    void encodeDeduplicatesAndTrimsAnswers() {
        assertEquals("[\"a\",\"b\"]", AcceptedAnswerCodec.encode(List.of(" a ", "b", "a", "b ")));
    }

    @Test
    void encodeDecodeRoundTripPreservesMultipleAnswers() {
        List<String> original = List.of("mvn test", "./mvnw test");
        String encoded = AcceptedAnswerCodec.encode(original);
        assertEquals(original, AcceptedAnswerCodec.decode(encoded));
    }

    @Test
    void normalizeConvertsMultilineUiInputIntoStoredFormat() {
        assertEquals("[\"first\",\"second\"]", AcceptedAnswerCodec.normalize("first\nsecond"));
        assertEquals("only", AcceptedAnswerCodec.normalize("only"));
    }
}
