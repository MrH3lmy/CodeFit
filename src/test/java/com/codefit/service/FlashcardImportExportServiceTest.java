package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.JavaCardConfig;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashcardImportExportServiceTest {

    @Test
    void toTsvRowJoinsFieldsWithTabs() {
        String row = FlashcardImportExportService.toTsvRow(List.of("front", "back", "RECALL"));
        assertEquals("front\tback\tRECALL", row);
    }

    @Test
    void toTsvRowRejectsEmbeddedTabsAndNewlines() {
        assertThrows(IllegalArgumentException.class,
                () -> FlashcardImportExportService.toTsvRow(List.of("front\twith tab", "back")));
        assertThrows(IllegalArgumentException.class,
                () -> FlashcardImportExportService.toTsvRow(List.of("front\nwith newline", "back")));
    }

    @Test
    void roundTripPreservesAcceptedAnswerCodecFormatThroughTsv() {
        String storedAnswers = AcceptedAnswerCodec.encode(List.of("@ControllerAdvice", "@RestControllerAdvice"));
        String row = FlashcardImportExportService.toTsvRow(List.of(
                FlashcardImportExportService.field("Which annotation?"),
                FlashcardImportExportService.field("@ControllerAdvice"),
                CardType.RECALL.name(),
                FlashcardImportExportService.field(storedAnswers),
                ValidationMode.CASE_INSENSITIVE.name(),
                FlashcardImportExportService.field(null),
                FlashcardImportExportService.field("Spring REST"),
                ""
        ));

        String[] parsedFields = row.split("\t", -1);
        assertEquals(storedAnswers, parsedFields[3]);
        assertEquals(List.of("@ControllerAdvice", "@RestControllerAdvice"), AcceptedAnswerCodec.decode(parsedFields[3]));
    }

    @Test
    void javaCodeExerciseConfigRoundTripsThroughTheExistingTsvColumnsUnchanged() {
        // JAVA_CODE reuses the accepted_answers column (like COMMAND_NORMALIZED cards already do)
        // instead of adding dedicated schema/TSV columns, so the existing 8-field format needs no
        // changes and every other card type's import/export stays exactly as it was.
        JavaCardConfig config = new JavaCardConfig(
                "public class Solution {\n public static void main(String[] a) {\n"
                        + JavaCardConfig.SOLUTION_PLACEHOLDER + "\n}\n}",
                "42\n", null, 5, 128);
        String encodedExercise = JavaExerciseCodec.encode(config);

        String row = FlashcardImportExportService.toTsvRow(List.of(
                FlashcardImportExportService.field("Print the answer to everything"),
                FlashcardImportExportService.field("System.out.println(42);"),
                CardType.JAVA_CODE.name(),
                FlashcardImportExportService.field(encodedExercise),
                ValidationMode.CASE_INSENSITIVE.name(),
                FlashcardImportExportService.field(null),
                FlashcardImportExportService.field("Java Fundamentals"),
                ""
        ));

        String[] parsedFields = row.split("\t", -1);
        assertEquals(8, parsedFields.length);
        assertEquals(encodedExercise, parsedFields[3]);
        JavaCardConfig decoded = JavaExerciseCodec.decode(parsedFields[3]);
        assertEquals(config.template(), decoded.template());
        assertEquals(config.expectedOutput(), decoded.expectedOutput());
    }

    @Test
    void roundTripPreservesSqlCardSpecFormatThroughTsv() {
        SqlCardSpec spec = new SqlCardSpec(
                "CREATE TABLE t (\n  id INTEGER PRIMARY KEY\n);",
                "INSERT INTO t VALUES (1);",
                "SELECT id FROM t;",
                null, true, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);
        String storedConfig = SqlCardSpecCodec.encode(spec);

        String row = FlashcardImportExportService.toTsvRow(List.of(
                FlashcardImportExportService.field("t(id): select every row."),
                FlashcardImportExportService.field("SELECT id FROM t;"),
                CardType.SQL_QUERY.name(),
                FlashcardImportExportService.field(storedConfig),
                ValidationMode.NORMALIZED_SPACING.name(),
                FlashcardImportExportService.field(null),
                FlashcardImportExportService.field("SQL"),
                ""
        ));

        String[] parsedFields = row.split("\t", -1);
        assertEquals(storedConfig, parsedFields[3]);
        assertEquals(spec, SqlCardSpecCodec.decode(AcceptedAnswerCodec.normalize(parsedFields[3])));
    }

    @Test
    void isHeaderRowRecognizesBasicAndExtendedHeaders() {
        assertTrue(FlashcardImportExportService.isHeaderRow("front\tback"));
        assertTrue(FlashcardImportExportService.isHeaderRow("Front\tBack\tCard_Type\tAccepted_Answers"));
        assertTrue(FlashcardImportExportService.isHeaderRow("front\tback\tcard_type\taccepted_answers"));
        assertTrue(!FlashcardImportExportService.isHeaderRow("extends\tJava keyword"));
    }

    @Test
    void blankToDefaultFallsBackOnlyWhenBlank() {
        assertEquals("fallback", FlashcardImportExportService.blankToDefault("", "fallback"));
        assertEquals("fallback", FlashcardImportExportService.blankToDefault(null, "fallback"));
        assertEquals("value", FlashcardImportExportService.blankToDefault("value", "fallback"));
    }

    @Test
    void blankToNullConvertsBlankToNull() {
        assertNull(FlashcardImportExportService.blankToNull(""));
        assertNull(FlashcardImportExportService.blankToNull(null));
        assertEquals("value", FlashcardImportExportService.blankToNull("value"));
    }

    @Test
    void parseTimeLimitAcceptsPositiveIntegersAndBlanks() {
        assertNull(FlashcardImportExportService.parseTimeLimit("", 1));
        assertNull(FlashcardImportExportService.parseTimeLimit(null, 1));
        assertEquals(45, FlashcardImportExportService.parseTimeLimit("45", 1));
    }

    @Test
    void parseTimeLimitRejectsZeroNegativeAndNonNumeric() {
        assertThrows(IllegalArgumentException.class, () -> FlashcardImportExportService.parseTimeLimit("0", 1));
        assertThrows(IllegalArgumentException.class, () -> FlashcardImportExportService.parseTimeLimit("-5", 1));
        assertThrows(IllegalArgumentException.class, () -> FlashcardImportExportService.parseTimeLimit("not-a-number", 1));
    }

    @Test
    void parseEnumFallsBackWhenBlankAndRejectsUnknownValues() {
        assertEquals(CardType.RECALL, FlashcardImportExportService.parseEnum(CardType.class, "", CardType.RECALL, "card_type", 1));
        assertEquals(CardType.SQL_QUERY,
                FlashcardImportExportService.parseEnum(CardType.class, "sql_query", CardType.RECALL, "card_type", 1));
        assertThrows(IllegalArgumentException.class,
                () -> FlashcardImportExportService.parseEnum(CardType.class, "NOT_A_TYPE", CardType.RECALL, "card_type", 1));
    }
}
