package com.codefit.service;

import java.util.List;

/**
 * The result of one {@link TrainingSheetImportService} run — either a real import or a
 * {@code dryRun} preview, which computes the exact same counts but rolls back every write.
 *
 * <p>{@code attemptsImported} and {@code reflectionFieldsImported} count the workbook's per-problem
 * phase timings/submission count/notes (as one {@code ProblemAttempt} snapshot) and perceived
 * difficulty/assistance level/actual topic/approach notes (on {@code ProblemProgress}) respectively
 * (#159) — both additive to the original {@code progressRecordsImported}, which continues to count
 * only workflow-state (e.g. {@code AC} -&gt; {@code SOLVED}) changes.
 *
 * <p>{@code details} is the richer breakdown behind these plain counts — per-stage counts, hyperlink
 * and platform coverage, status/topic distribution, and rows skipped grouped by reason — that the
 * import preview screen (#160) shows before the learner commits to a real import. It comes from the
 * exact same row-by-row pass as everything else in this summary, whether this run was a real import
 * or a {@link TrainingSheetImportService#preview} dry run.
 */
public record TrainingSheetImportSummary(boolean dryRun, int problemsCreated, int problemsUpdated, int problemsReused,
                                         int roadmapMembershipsCreated, int progressRecordsImported,
                                         int duplicateRowsSkipped, int invalidRows, int attemptsImported,
                                         int reflectionFieldsImported, List<String> warnings, Long importBatchId,
                                         WorkbookPreviewDetails details) {

    public String message() {
        String prefix = dryRun ? "Preview: would create " : "Created ";
        return prefix + problemsCreated + " problem(s) and " + roadmapMembershipsCreated + " roadmap membership(s); "
                + "reused " + problemsReused + " existing problem(s) (" + problemsUpdated + " updated); "
                + "imported " + progressRecordsImported + " progress record(s), " + attemptsImported
                + " attempt snapshot(s), and " + reflectionFieldsImported + " reflection field set(s); "
                + "skipped " + duplicateRowsSkipped + " duplicate row(s) and " + invalidRows + " invalid row(s)."
                + (warnings.isEmpty() ? "" : " " + warnings.size() + " warning(s).");
    }
}
