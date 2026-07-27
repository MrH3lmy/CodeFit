package com.codefit.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
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

/**
 * Reads a Junior Training Sheet-style {@code .xlsx} workbook (roadmap sheets plus an optional
 * {@code Topics} sheet) into a plain in-memory {@link ParsedWorkbook}, entirely separate from the
 * import/matching logic in {@link TrainingSheetImportService}. Every cell is read through POI's
 * {@link DataFormatter} against the evaluated formula result, so numeric, text, and formula cells
 * are all read the same way without hand-rolled per-type branching.
 *
 * <p>A sheet's header row (its first non-empty row) is matched against
 * {@link TrainingSheetColumns#canonicalize}; unrecognized header columns are ignored rather than
 * rejected, and rows that are entirely blank (e.g. a spacer row) are skipped.
 */
final class TrainingSheetWorkbookReader {

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
        return new ParsedWorkbook(sheets);
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

        List<ParsedWorkbookRow> rows = new ArrayList<>();
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : canonicalColumnByIndex.entrySet()) {
                Cell cell = row.getCell(entry.getKey());
                String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator).strip();
                values.put(entry.getValue(), value);
            }
            if (values.values().stream().allMatch(String::isBlank)) {
                continue;
            }
            // Spreadsheet row numbers are 1-based; rowIndex is 0-based.
            rows.add(new ParsedWorkbookRow(rowIndex + 1, values));
        }

        return new ParsedSheet(sheet.getSheetName(), new LinkedHashSet<>(canonicalColumnByIndex.values()), rows);
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
                if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK) {
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
