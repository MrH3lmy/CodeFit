package com.codefit.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds small, synthetic {@code .xlsx} workbook fixtures in-memory/on a temp directory for
 * importer tests (#143). Never reads or copies the real Junior Training Sheet — every fixture here
 * is authored from scratch with made-up problems.
 */
final class TrainingSheetFixtures {

    private TrainingSheetFixtures() {
    }

    record SheetSpec(String name, List<String> headers, List<List<String>> rows) {
    }

    static Path writeWorkbook(Path directory, String fileName, List<SheetSpec> sheets) throws IOException {
        Path path = directory.resolve(fileName);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (SheetSpec spec : sheets) {
                Sheet sheet = workbook.createSheet(spec.name());
                Row headerRow = sheet.createRow(0);
                List<String> headers = spec.headers();
                for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                    headerRow.createCell(columnIndex).setCellValue(headers.get(columnIndex));
                }
                List<List<String>> rows = spec.rows();
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    Row row = sheet.createRow(rowIndex + 1);
                    List<String> values = rows.get(rowIndex);
                    for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                        String value = values.get(columnIndex);
                        if (value != null) {
                            row.createCell(columnIndex).setCellValue(value);
                        }
                    }
                }
            }
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                workbook.write(outputStream);
            }
        }
        return path;
    }
}
