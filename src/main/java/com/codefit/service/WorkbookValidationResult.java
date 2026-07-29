package com.codefit.service;

import java.util.List;

/**
 * The result of validating a workbook's structure before importing anything from it. {@code valid}
 * is {@code true} as long as at least one roadmap stage sheet (A, B, C1, C2, D1, D2, D3) is present
 * with recognizable problem code/title columns; {@code structuralWarnings} explains any sheets or
 * stages that were missing or unusable, even when the workbook is otherwise valid.
 *
 * @param recognizedSheets sheet names the importer will actually read from: usable roadmap stage
 *                          sheets plus {@code Topics} when present
 * @param ignoredSheets     sheet names present in the workbook but not used at all: extra sheets that
 *                          aren't a recognized roadmap stage or {@code Topics}, and roadmap stage
 *                          sheets present but missing recognizable code/title columns
 * @param diagnostics       {@code structuralWarnings} as structured {@link TrainingSheetDiagnostic}s;
 *                          {@code BLOCKING} exactly when {@code valid} is {@code false}
 */
public record WorkbookValidationResult(boolean valid, List<String> missingRoadmapSheets, List<String> structuralWarnings,
                                       List<String> recognizedSheets, List<String> ignoredSheets,
                                       List<TrainingSheetDiagnostic> diagnostics) {
}
