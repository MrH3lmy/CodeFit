package com.codefit.service;

import com.codefit.model.RoadmapStage;

import java.util.Map;

/**
 * The rich, human-facing breakdown behind a {@link TrainingSheetImportSummary} (#160): what the
 * learner sees on the "review and confirm" screen before committing an import, beyond the summary's
 * plain created/updated/reused counts. Computed by the exact same row-by-row pass
 * {@link TrainingSheetImportService} uses for a real import or a {@link TrainingSheetImportService#preview}
 * dry run — never a second, separately-maintained pass over the workbook — so the preview and the
 * import it describes can never drift apart.
 *
 * @param stageMembershipCounts   roadmap memberships processed per stage (e.g. Stage B -&gt; 172)
 * @param hyperlinksFound         valid rows whose problem link was resolved (explicit URL column,
 *                                native hyperlink, or a {@code =HYPERLINK()} formula)
 * @param hyperlinksMissing       valid rows with no resolvable link at all
 * @param platformCounts          judge/platform name (explicit column or inferred) -&gt; row count
 * @param solvedCount             valid rows whose status column resolves to {@code SOLVED}
 * @param inProgressCount         valid rows whose status column resolves to {@code IN_PROGRESS}
 * @param revisitCount            valid rows whose status column resolves to {@code NEEDS_REVISIT}
 * @param topicCounts             topic/category value -&gt; row count, across roadmap and Topics rows
 * @param qualityMetadataCount    rows carrying a recognized 1-5 quality rating
 * @param rowsSkippedByReason     every dropped/rejected row, grouped by human-readable reason (e.g.
 *                                {@code "blank row"}, {@code "aggregate row"},
 *                                {@code "sample placeholder row"}, {@code "missing problem code or title"},
 *                                {@code "duplicate problem code within sheet"}, {@code "roadmap slot conflict"})
 */
public record WorkbookPreviewDetails(Map<RoadmapStage, Integer> stageMembershipCounts, int hyperlinksFound,
                                     int hyperlinksMissing, Map<String, Integer> platformCounts, int solvedCount,
                                     int inProgressCount, int revisitCount, Map<String, Integer> topicCounts,
                                     int qualityMetadataCount, Map<String, Integer> rowsSkippedByReason) {
}
