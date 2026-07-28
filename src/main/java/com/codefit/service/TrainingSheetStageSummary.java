package com.codefit.service;

import com.codefit.model.RoadmapStage;

/**
 * One roadmap stage's row accounting in the preview (#160). A stage membership count alone isn't the
 * same as "how many rows were in the sheet" — instructional/sample/aggregate rows are dropped by
 * {@link TrainingSheetWorkbookReader} before analysis even sees them, and duplicate/invalid/conflicting
 * rows are dropped by {@link TrainingSheetAnalyzer} — so this reports all three explicitly.
 *
 * @param stage             the roadmap stage
 * @param detectedRows      every row slot found below the sheet's header, including entirely blank ones
 * @param validRows         rows that became a roadmap membership (equal to {@link #roadmapMemberships()})
 * @param skippedRows       {@code detectedRows - validRows}: everything dropped, for any reason
 * @param roadmapMemberships the number of {@link AnalyzedRoadmapMembership}s this stage produced
 */
public record TrainingSheetStageSummary(RoadmapStage stage, int detectedRows, int validRows, int skippedRows,
                                        int roadmapMemberships) {
}
