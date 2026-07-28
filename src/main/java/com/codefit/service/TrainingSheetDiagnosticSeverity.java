package com.codefit.service;

/**
 * How serious a {@link TrainingSheetDiagnostic} is (#160). {@code WARNING} means the affected
 * row/sheet was skipped but the rest of the workbook can still be imported; {@code BLOCKING} means
 * there is nothing importable at all (e.g. no usable roadmap sheet), so the import screen must keep
 * the "Import Now" action disabled until the workbook is fixed.
 */
public enum TrainingSheetDiagnosticSeverity {
    WARNING,
    BLOCKING
}
