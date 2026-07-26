package com.codefit.service;

import java.util.List;

/**
 * The result of validating a workbook's structure before importing anything from it. {@code valid}
 * is {@code true} as long as at least one roadmap stage sheet (A, B, C1, C2, D1, D2, D3) is present
 * with recognizable problem code/title columns; {@code structuralWarnings} explains any sheets or
 * stages that were missing or unusable, even when the workbook is otherwise valid.
 */
public record WorkbookValidationResult(boolean valid, List<String> missingRoadmapSheets, List<String> structuralWarnings) {
}
