package com.codefit.service;

import java.util.List;

/**
 * A workbook fully analyzed by {@link TrainingSheetAnalyzer}, with no database access involved at any
 * point (#160): every problem, roadmap membership, diagnostic, and content count is derived purely
 * from the workbook's own cells. The preview screen and a confirmed import both consume this exact
 * object — {@link TrainingSheetImportService#previewOf} renders it read-only, and
 * {@link TrainingSheetImportService#importAnalyzed} applies it transactionally — so what the learner
 * reviewed and what gets written can never drift apart, and the source file is never re-read after
 * analysis.
 *
 * @param workbookName    the workbook's display name (its file name)
 * @param problems        every unique problem found, deduplicated by {@code (platform, externalCode)}
 * @param memberships     every valid roadmap-slot registration found, referencing a problem in
 *                        {@code problems} by its {@link AnalyzedProblem#key()}
 * @param details         the workbook-content counts and coverage breakdown (#160)
 * @param diagnostics     every finding from analysis, severity-tagged (see {@link #hasBlockingDiagnostics()})
 * @param fileFingerprint an informational snapshot of the source file's identity (name, size, and last
 *                        modified time) at analysis time, for traceability only — importing never
 *                        depends on re-reading or re-fingerprinting the file
 */
public record AnalyzedTrainingWorkbook(
        String workbookName,
        List<AnalyzedProblem> problems,
        List<AnalyzedRoadmapMembership> memberships,
        WorkbookPreviewDetails details,
        List<TrainingSheetDiagnostic> diagnostics,
        String fileFingerprint) {

    /** {@code true} when this workbook has nothing importable at all (e.g. no usable roadmap sheet) —
     *  the import preview screen must keep "Import Now" disabled while this holds. */
    public boolean hasBlockingDiagnostics() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.BLOCKING);
    }
}
