package com.codefit.service;

/**
 * One actionable finding from analyzing or importing a workbook (#160): where it happened (sheet,
 * 1-based spreadsheet row, and column when known) and why, tagged with a {@link TrainingSheetDiagnosticSeverity}
 * so the import preview screen can tell a learner "this row was skipped" apart from "nothing here can
 * be imported at all". {@code sheet}, {@code row}, and {@code column} are all nullable: a workbook-wide
 * finding (e.g. no usable roadmap sheet at all) has no specific sheet/row/column to point to.
 */
public record TrainingSheetDiagnostic(String sheet, Integer row, String column, String reason,
                                      TrainingSheetDiagnosticSeverity severity) {

    /** Renders {@code "Sheet X, row Y, column Z: reason"}, omitting any part that's {@code null}. */
    public String describe() {
        StringBuilder location = new StringBuilder();
        if (sheet != null) {
            location.append("Sheet ").append(sheet);
        }
        if (row != null) {
            location.append(location.isEmpty() ? "Row " : ", row ").append(row);
        }
        if (column != null) {
            location.append(location.isEmpty() ? "Column " : ", column ").append(column);
        }
        return location.isEmpty() ? reason : location + ": " + reason;
    }
}
