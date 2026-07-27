package com.codefit.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One sheet from a parsed workbook: its name, the canonical columns its header row recognized (see
 * {@link TrainingSheetColumns}), its non-blank/non-instructional data rows, and a breakdown of how
 * many rows were dropped before reaching {@link #rows()} and why (#159/#160) — e.g. {@code "blank"},
 * {@code "aggregate row"}, {@code "sample placeholder row"} — for the import preview report.
 */
record ParsedSheet(String name, Set<String> recognizedColumns, List<ParsedWorkbookRow> rows,
                   Map<String, Integer> droppedRowReasons) {

    ParsedSheet(String name, Set<String> recognizedColumns, List<ParsedWorkbookRow> rows) {
        this(name, recognizedColumns, rows, Map.of());
    }

    boolean hasColumn(String canonicalColumn) {
        return recognizedColumns.contains(canonicalColumn);
    }
}
