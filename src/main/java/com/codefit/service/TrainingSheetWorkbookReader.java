package com.codefit.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Junior Training Sheet-style {@code .xlsx} workbook (roadmap sheets plus an optional
 * {@code Topics} sheet) into a plain in-memory {@link ParsedWorkbook}, entirely separate from the
 * import/matching logic in {@link TrainingSheetImportService}. Every cell is read through POI's
 * {@link DataFormatter} against the evaluated formula result, so numeric, text, and formula cells
 * are all read the same way without hand-rolled per-type branching. Notably, POI's
 * {@link FormulaEvaluator} evaluates a {@code =HYPERLINK(url, "text")} formula cell to {@code "text"}
 * even when the workbook has no cached value for it, unlike tools that only trust a cached formula
 * result (#159).
 *
 * <p>A sheet's header row (its first non-empty row) is matched against
 * {@link TrainingSheetColumns#canonicalize}; unrecognized header columns are ignored rather than
 * rejected, and rows that are entirely blank (e.g. a spacer row) are skipped.
 *
 * <p>Two real-workbook accommodations (#159) live here rather than in the importer, since they are
 * about recognizing the workbook's shape, not about matching/import semantics:
 * <ul>
 *   <li>A header row whose problem-code column is recognized but whose immediately preceding column
 *       has no (or unrecognized) header text is assumed to be the title column anyway — the real
 *       workbook's "B" stage sheet omits that one header label while every other stage spells it out,
 *       and the column position is otherwise identical across every roadmap sheet.</li>
 *   <li>Rows that are clearly not real problems — an aggregate "AC Averages =&gt;" row, or one of the
 *       real workbook's five literal "Sample Name/Link" placeholder rows — are dropped here, the same
 *       way an entirely blank row is, so they never reach the importer as (or generate warnings about)
 *       invalid data.</li>
 * </ul>
 */
final class TrainingSheetWorkbookReader {

    private static final Pattern SAMPLE_ROW_PATTERN = Pattern.compile("^Sample (Name|Link)\\d*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HYPERLINK_FORMULA_PATTERN =
            Pattern.compile("HYPERLINK\\(\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Matches a cell whose text mentions "version" alongside a version-shaped token, e.g. the real
     *  workbook's "Currenet Version V7.0" cell (#160) - content-based, never derived from the file name. */
    private static final Pattern VERSION_MENTION_PATTERN =
            Pattern.compile("version[^0-9A-Za-z]*([vV]?\\d+(?:\\.\\d+)*)", Pattern.CASE_INSENSITIVE);
    /** Fallback: a cell whose entire (trimmed) content is just a version-shaped token on its own. */
    private static final Pattern STANDALONE_VERSION_PATTERN = Pattern.compile("^[vV]\\d+(?:\\.\\d+){1,2}$");

    /** Synthetic column keys, not header-matched: hyperlink targets recovered from the code/title cells (#159). */
    static final String CODE_URL = "CODE_URL";
    static final String TITLE_URL = "TITLE_URL";

    private TrainingSheetWorkbookReader() {
    }

    static ParsedWorkbook read(Path workbookPath) {
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            return readWorkbook(workbook);
        } catch (IOException | RuntimeException exception) {
            throw new WorkbookImportException("Unable to read workbook file '" + workbookPath.getFileName() + "': "
                    + exception.getMessage(), exception);
        }
    }

    private static ParsedWorkbook readWorkbook(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        Map<String, ParsedSheet> sheets = new LinkedHashMap<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            sheets.put(sheet.getSheetName(), readSheet(sheet, formatter, evaluator));
        }
        return new ParsedWorkbook(sheets, detectVersion(workbook, formatter, evaluator));
    }

    /**
     * Scans every cell of every sheet (not just recognized roadmap columns) for a version marker
     * (#160), honestly derived from the workbook's own content: a cell mentioning "version" alongside
     * a version-shaped token (e.g. the real workbook's "Currenet Version V7.0" cell on its Info sheet),
     * or, failing that, any cell whose entire content is just a standalone version-shaped token.
     * Returns {@code null} rather than guessing when neither is found — never derived from the file name.
     */
    private static String detectVersion(Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator) {
        String standaloneFallback = null;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String text = formatCellValueSafely(cell, formatter, evaluator);
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    Matcher mentionMatcher = VERSION_MENTION_PATTERN.matcher(text);
                    if (mentionMatcher.find()) {
                        return mentionMatcher.group(1);
                    }
                    if (standaloneFallback == null && STANDALONE_VERSION_PATTERN.matcher(text.strip()).matches()) {
                        standaloneFallback = text.strip();
                    }
                }
            }
        }
        return standaloneFallback;
    }

    private static ParsedSheet readSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row headerRow = findHeaderRow(sheet);
        if (headerRow == null) {
            return new ParsedSheet(sheet.getSheetName(), Set.of(), List.of());
        }

        Map<Integer, String> canonicalColumnByIndex = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String canonical = TrainingSheetColumns.canonicalize(formatter.formatCellValue(cell));
            if (canonical != null) {
                canonicalColumnByIndex.put(cell.getColumnIndex(), canonical);
            }
        }
        applyTitleBeforeCodePositionalFallback(canonicalColumnByIndex);

        List<ParsedWorkbookRow> rows = new ArrayList<>();
        Map<String, Integer> droppedRowReasons = new LinkedHashMap<>();
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : canonicalColumnByIndex.entrySet()) {
                Cell cell = row.getCell(entry.getKey());
                values.put(entry.getValue(), formatCellValueSafely(cell, formatter, evaluator));
            }
            addHyperlinkIfPresent(row, canonicalColumnByIndex, TrainingSheetColumns.CODE, CODE_URL, values);
            addHyperlinkIfPresent(row, canonicalColumnByIndex, TrainingSheetColumns.TITLE, TITLE_URL, values);

            String skipReason = blankOrInstructionalRowReason(values);
            if (skipReason != null) {
                droppedRowReasons.merge(skipReason, 1, Integer::sum);
                continue;
            }
            // Spreadsheet row numbers are 1-based; rowIndex is 0-based.
            rows.add(new ParsedWorkbookRow(rowIndex + 1, values));
        }

        // Every row slot below the header, including entirely-empty ones the loop above never turns
        // into a ParsedWorkbookRow at all - the raw "detected rows" a preview reports (#160), as
        // opposed to validRows/skippedRows which only make sense relative to this raw total.
        int detectedRowCount = Math.max(0, sheet.getLastRowNum() - headerRow.getRowNum());
        return new ParsedSheet(sheet.getSheetName(), new LinkedHashSet<>(canonicalColumnByIndex.values()), rows,
                droppedRowReasons, detectedRowCount);
    }

    /**
     * If the header row recognized a problem-code column but not a title column, and the column
     * immediately before the code column exists and has no recognized header of its own, treat that
     * column as the title column. This is the real workbook's "B" stage sheet: every other roadmap
     * sheet spells out a title header in that position, but its position (immediately left of the
     * code column) is identical across every sheet.
     */
    private static void applyTitleBeforeCodePositionalFallback(Map<Integer, String> canonicalColumnByIndex) {
        if (canonicalColumnByIndex.containsValue(TrainingSheetColumns.TITLE)) {
            return;
        }
        Integer codeColumnIndex = null;
        for (Map.Entry<Integer, String> entry : canonicalColumnByIndex.entrySet()) {
            if (TrainingSheetColumns.CODE.equals(entry.getValue())) {
                codeColumnIndex = entry.getKey();
                break;
            }
        }
        if (codeColumnIndex == null || codeColumnIndex < 1) {
            return;
        }
        int titleColumnIndex = codeColumnIndex - 1;
        if (!canonicalColumnByIndex.containsKey(titleColumnIndex)) {
            canonicalColumnByIndex.put(titleColumnIndex, TrainingSheetColumns.TITLE);
        }
    }

    /**
     * Formats one cell's value, falling back to its last-saved cached value (or blank) if POI's
     * formula evaluator can't evaluate it. A real-world workbook can carry aggregate/summary formulas
     * (e.g. an averages row using a function POI's evaluator doesn't implement) in cells this reader
     * never treats as real problem data anyway (see {@link #isBlankOrInstructionalRow}); a formula POI
     * can't evaluate must never fail the whole import over a row that was going to be skipped regardless.
     */
    private static String formatCellValueSafely(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator).strip();
        } catch (RuntimeException unevaluableFormula) {
            try {
                return formatter.formatCellValue(cell).strip();
            } catch (RuntimeException stillUnreadable) {
                return "";
            }
        }
    }

    /** Recovers a hyperlink target for {@code canonicalColumn}'s cell (native hyperlink, or the URL
     *  argument of a {@code =HYPERLINK(url, text)} formula) into {@code syntheticKey}, if present. */
    private static void addHyperlinkIfPresent(Row row, Map<Integer, String> canonicalColumnByIndex,
                                              String canonicalColumn, String syntheticKey, Map<String, String> values) {
        Integer columnIndex = null;
        for (Map.Entry<Integer, String> entry : canonicalColumnByIndex.entrySet()) {
            if (canonicalColumn.equals(entry.getValue())) {
                columnIndex = entry.getKey();
                break;
            }
        }
        if (columnIndex == null) {
            return;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return;
        }
        Hyperlink hyperlink = cell.getHyperlink();
        if (hyperlink != null && hyperlink.getAddress() != null && !hyperlink.getAddress().isBlank()) {
            values.put(syntheticKey, hyperlink.getAddress().strip());
            return;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            Matcher matcher = HYPERLINK_FORMULA_PATTERN.matcher(cell.getCellFormula());
            if (matcher.find()) {
                values.put(syntheticKey, matcher.group(1).strip());
            }
        }
    }

    /**
     * The reason a row is dropped here (never reaching the importer) — blank, an aggregate/summary
     * row, or one of the real workbook's literal "Sample Name/Link" placeholder rows (#159/#160) — or
     * {@code null} if it isn't one of those. A row with a code or title that merely fails other
     * validation (e.g. one of the two present but not the other) still reaches the importer, which
     * reports it as an invalid row rather than silently dropping it.
     */
    private static String blankOrInstructionalRowReason(Map<String, String> values) {
        String title = blankToNull(values.get(TrainingSheetColumns.TITLE));
        String code = blankToNull(values.get(TrainingSheetColumns.CODE));
        if (title == null && code == null) {
            return "blank row";
        }
        if (code != null && code.toLowerCase(java.util.Locale.ROOT).contains("average")) {
            return "aggregate row";
        }
        if ((title != null && SAMPLE_ROW_PATTERN.matcher(title).matches())
                || (code != null && SAMPLE_ROW_PATTERN.matcher(code).matches())) {
            return "sample placeholder row";
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The first row in the sheet that isn't entirely empty, treated as the header row. */
    private static Row findHeaderRow(Sheet sheet) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            boolean hasContent = false;
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.BLANK) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                return row;
            }
        }
        return null;
    }
}
