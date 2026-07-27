package com.codefit.service;

import java.util.List;
import java.util.Set;

/**
 * One sheet from a parsed workbook: its name, the canonical columns its header row recognized (see
 * {@link TrainingSheetColumns}), and its non-blank data rows.
 */
record ParsedSheet(String name, Set<String> recognizedColumns, List<ParsedWorkbookRow> rows) {

    boolean hasColumn(String canonicalColumn) {
        return recognizedColumns.contains(canonicalColumn);
    }
}
