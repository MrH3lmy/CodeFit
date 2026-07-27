package com.codefit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingSheetWorkbookReaderTest {

    @Test
    void readsRecognizedHeadersAndDataRows(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "reader-basic.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("A",
                        List.of("Code", "Title", "Platform", "Link", "Level"),
                        List.of(
                                List.of("A-1", "Two Sum", "LeetCode", "https://example.test/a1", "Easy"),
                                List.of("A-2", "Reverse String", "LeetCode", "https://example.test/a2", "Easy")))));

        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);

        Optional<ParsedSheet> sheetA = workbook.sheet("A");
        assertTrue(sheetA.isPresent());
        assertTrue(sheetA.get().hasColumn(TrainingSheetColumns.CODE));
        assertTrue(sheetA.get().hasColumn(TrainingSheetColumns.TITLE));
        assertEquals(2, sheetA.get().rows().size());

        ParsedWorkbookRow firstRow = sheetA.get().rows().get(0);
        assertEquals("A-1", firstRow.get(TrainingSheetColumns.CODE));
        assertEquals("Two Sum", firstRow.get(TrainingSheetColumns.TITLE));
        assertEquals("Easy", firstRow.get(TrainingSheetColumns.LEVEL));
    }

    @Test
    void unrecognizedHeaderColumnsAreIgnoredRatherThanRejected(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "reader-unknown-header.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("A",
                        List.of("Code", "Title", "Author's Private Comment"),
                        List.of(List.of("A-1", "Two Sum", "ignore me")))));

        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);

        ParsedSheet sheetA = workbook.sheet("A").orElseThrow();
        assertFalse(sheetA.recognizedColumns().contains("Author's Private Comment"));
        assertEquals(1, sheetA.rows().size());
    }

    @Test
    void blankSpacerRowsAreSkipped(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "reader-blank-row.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("A",
                        List.of("Code", "Title"),
                        List.of(
                                List.of("A-1", "Two Sum"),
                                List.of("", ""),
                                List.of("A-2", "Reverse String")))));

        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);

        assertEquals(2, workbook.sheet("A").orElseThrow().rows().size());
    }

    @Test
    void missingSheetIsAbsentFromTheParsedWorkbook(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "reader-missing-sheet.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("A", List.of("Code", "Title"), List.of())));

        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);

        assertTrue(workbook.sheet("B").isEmpty());
    }

    @Test
    void rowNumbersAreOneBasedSpreadsheetRowNumbers(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "reader-row-numbers.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("A",
                        List.of("Code", "Title"),
                        List.of(
                                List.of("A-1", "Two Sum"),
                                List.of("A-2", "Reverse String")))));

        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);
        List<ParsedWorkbookRow> rows = workbook.sheet("A").orElseThrow().rows();

        // Header is row 1; the first data row is spreadsheet row 2, the second is row 3.
        assertEquals(2, rows.get(0).rowNumber());
        assertEquals(3, rows.get(1).rowNumber());
    }

    @Test
    void unreadableFileRaisesAWorkbookImportException(@TempDir Path tempDir) throws Exception {
        Path notAWorkbook = tempDir.resolve("not-a-workbook.xlsx");
        java.nio.file.Files.writeString(notAWorkbook, "this is not a real xlsx file");

        try {
            TrainingSheetWorkbookReader.read(notAWorkbook);
            org.junit.jupiter.api.Assertions.fail("expected a WorkbookImportException");
        } catch (WorkbookImportException expected) {
            assertTrue(expected.getMessage().contains("not-a-workbook.xlsx"));
        }
    }
}
