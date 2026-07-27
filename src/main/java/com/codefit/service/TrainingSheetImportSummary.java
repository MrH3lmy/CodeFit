package com.codefit.service;

import java.util.List;

/**
 * The result of one {@link TrainingSheetImportService} run — either a real import or a
 * {@code dryRun} preview, which computes the exact same counts but rolls back every write.
 */
public record TrainingSheetImportSummary(boolean dryRun, int problemsCreated, int problemsUpdated, int problemsReused,
                                         int roadmapMembershipsCreated, int progressRecordsImported,
                                         int duplicateRowsSkipped, int invalidRows, List<String> warnings) {

    public String message() {
        String prefix = dryRun ? "Preview: would create " : "Created ";
        return prefix + problemsCreated + " problem(s) and " + roadmapMembershipsCreated + " roadmap membership(s); "
                + "reused " + problemsReused + " existing problem(s) (" + problemsUpdated + " updated); "
                + "imported " + progressRecordsImported + " progress record(s); "
                + "skipped " + duplicateRowsSkipped + " duplicate row(s) and " + invalidRows + " invalid row(s)."
                + (warnings.isEmpty() ? "" : " " + warnings.size() + " warning(s).");
    }
}
