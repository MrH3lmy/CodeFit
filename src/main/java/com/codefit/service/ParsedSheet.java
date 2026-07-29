package com.codefit.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One sheet from a parsed workbook: its name, the canonical columns its header row recognized (see
 * {@link TrainingSheetColumns}), its non-blank/non-instructional data rows, a breakdown of how many
 * rows were dropped before reaching {@link #rows()} and why (#159/#160) — e.g. {@code "blank"},
 * {@code "aggregate row"}, {@code "sample placeholder row"} — for the import preview report, and the
 * raw detected row count (#160): every row slot below the header, including completely empty ones the
 * reader never even builds a {@link ParsedWorkbookRow} for — so a stage's "skipped rows" can be
 * reported as {@code detectedRowCount - validRows} without under-counting.
 */
record ParsedSheet(String name, Set<String> recognizedColumns, List<ParsedWorkbookRow> rows,
                   Map<String, Integer> droppedRowReasons, int detectedRowCount) {

    ParsedSheet(String name, Set<String> recognizedColumns, List<ParsedWorkbookRow> rows) {
        this(name, recognizedColumns, rows, Map.of(), 0);
    }

    boolean hasColumn(String canonicalColumn) {
        return recognizedColumns.contains(canonicalColumn);
    }
}
