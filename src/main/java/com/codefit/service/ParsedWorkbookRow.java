package com.codefit.service;

import java.util.Map;

/**
 * One data row from a parsed workbook sheet: its 1-based spreadsheet row number (for user-facing
 * error/warning messages) and its cell values keyed by the canonical column names from
 * {@link TrainingSheetColumns}. Columns the sheet didn't recognize simply aren't present in the map.
 */
record ParsedWorkbookRow(int rowNumber, Map<String, String> valuesByColumn) {

    String get(String canonicalColumn) {
        String value = valuesByColumn.get(canonicalColumn);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
