package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.RoadmapStage;

/**
 * One roadmap-slot registration found in an analyzed workbook (#160): {@code problemKey} resolves to
 * an {@link AnalyzedProblem} in the same {@link AnalyzedTrainingWorkbook}. Duplicate-within-sheet rows
 * and rows whose explicit position conflicts with another row <em>in this same workbook</em> never
 * become a membership at all — those are reported as {@link TrainingSheetDiagnostic}s during analysis
 * instead. A conflict against a completely different, already-imported workbook can only be detected
 * once a real database connection is available, so that (rarer) case is still caught at import time by
 * {@link TrainingSheetImportService#importAnalyzed}.
 */
public record AnalyzedRoadmapMembership(
        String problemKey,
        RoadmapStage stage,
        int sequenceOrder,
        Integer setNumber,
        boolean mandatory,
        DifficultyLevel suggestedLevel) {
}
