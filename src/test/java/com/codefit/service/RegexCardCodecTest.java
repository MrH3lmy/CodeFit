package com.codefit.service;

import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexCardFlag;
import com.codefit.model.RegexMatchMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexCardCodecTest {

    @Test
    void encodeThenDecodeRoundTripsAllFields() {
        RegexCardConfig config = new RegexCardConfig(
                List.of("555-1234", "555-0000"),
                List.of("abc", "555-12345"),
                Set.of(RegexCardFlag.CASE_INSENSITIVE, RegexCardFlag.MULTILINE),
                RegexMatchMode.FULL_MATCH);

        String encoded = RegexCardCodec.encode(config);
        RegexCardConfig decoded = RegexCardCodec.decode(encoded);

        assertEquals(config.mustMatch(), decoded.mustMatch());
        assertEquals(config.mustNotMatch(), decoded.mustNotMatch());
        assertEquals(config.flags(), decoded.flags());
        assertEquals(config.matchMode(), decoded.matchMode());
    }

    @Test
    void encodedConfigIsSingleLineSoItSurvivesTsvExport() {
        RegexCardConfig config = new RegexCardConfig(
                List.of("line one\nline two", "tab\tcharacter"),
                List.of("quote\"inside"),
                Set.of(RegexCardFlag.DOTALL),
                RegexMatchMode.FIND);

        String encoded = RegexCardCodec.encode(config);
        assertFalse(encoded.contains("\n"));
        assertFalse(encoded.contains("\t"));

        RegexCardConfig decoded = RegexCardCodec.decode(encoded);
        assertEquals(config.mustMatch(), decoded.mustMatch());
        assertEquals(config.mustNotMatch(), decoded.mustNotMatch());
    }

    @Test
    void decodeOfBlankOrMalformedInputIsEmptyInsteadOfThrowing() {
        assertTrue(RegexCardCodec.decode(null).mustMatch().isEmpty());
        assertTrue(RegexCardCodec.decode("").mustMatch().isEmpty());
        assertTrue(RegexCardCodec.decode("not json at all").mustMatch().isEmpty());
        assertTrue(RegexCardCodec.decode("{\"mustMatch\":[\"unterminated").mustMatch().isEmpty());
    }

    @Test
    void decodeDefaultsMatchModeToFindWhenAbsent() {
        RegexCardConfig decoded = RegexCardCodec.decode("{\"mustMatch\":[\"a\"],\"mustNotMatch\":[],\"flags\":[]}");
        assertEquals(RegexMatchMode.FIND, decoded.matchMode());
        assertEquals(List.of("a"), decoded.mustMatch());
    }

    @Test
    void decodeAcceptsUnicodeEscapesInsideExamples() {
        RegexCardConfig decoded = RegexCardCodec.decode(
                "{\"mustMatch\":[\"tab\\u0009end\"],\"mustNotMatch\":[],\"flags\":[],\"matchMode\":\"FIND\"}");
        assertEquals("tab\tend", decoded.mustMatch().get(0));
    }
}
