package com.codefit.service;

import com.codefit.model.RoadmapStage;

import java.util.List;
import java.util.Map;

/**
 * The rich, human-facing breakdown of an {@link AnalyzedTrainingWorkbook} (#160): what the learner
 * sees on the "review and confirm" screen before committing an import. Every field here describes the
 * <em>workbook's own content</em> — never the database's current state — so it reads identically
 * whether the workbook has never been imported before or is being re-imported for the tenth time (see
 * {@link #uniqueProblemCount()}/{@link #roadmapMembershipCount()} versus a {@link TrainingSheetImportSummary}'s
 * separate created/updated/reused counts, which <em>do</em> depend on what's already in the database).
 *
 * @param uniqueProblemCount        distinct {@code (platform, externalCode)} problems found, across every sheet
 * @param roadmapMembershipCount    total valid roadmap-slot registrations found, across every stage
 * @param stageMembershipCounts     roadmap memberships per stage; every {@link RoadmapStage} is present,
 *                                  0 for a stage with no memberships
 * @param hyperlinksFound           valid rows whose problem link was resolved (explicit URL column,
 *                                  native hyperlink, or a {@code =HYPERLINK()} formula)
 * @param hyperlinksMissing         valid rows with no resolvable link at all
 * @param platformCounts            judge/platform name (explicit column or inferred) -&gt; row count
 * @param explicitPlatformCount     valid rows whose platform came from an explicit Platform column
 * @param inferredPlatformCount     valid rows with no Platform column whose platform was inferred from
 *                                  the code/URL (see {@link PlatformInference})
 * @param unknownPlatformCount      valid rows with no Platform column and no inferrable platform,
 *                                  falling back to a generic default
 * @param solvedCount                valid rows whose status column resolves to {@code SOLVED}
 * @param inProgressCount            valid rows whose status column resolves to {@code IN_PROGRESS}
 * @param revisitCount                valid rows whose status column resolves to {@code NEEDS_REVISIT}
 * @param notStartedCount            valid rows with no recognized advancing status
 * @param topicCounts                topic/category value -&gt; row count, across roadmap and Topics rows
 * @param qualityMetadataCount       rows carrying a recognized 1-5 quality rating
 * @param suggestedLevelMetadataCount rows carrying a recognized Easy/Medium/Hard suggested level
 * @param assistanceMetadataCount    rows carrying a recognized "by yourself?" independence value
 * @param rowsSkippedByReason        every dropped/rejected row, grouped by human-readable reason (e.g.
 *                                  {@code "blank row"}, {@code "aggregate row"},
 *                                  {@code "sample placeholder row"}, {@code "missing problem code or title"},
 *                                  {@code "duplicate problem code within sheet"}, {@code "roadmap slot conflict"})
 * @param duplicateRowsSkipped        rows dropped as a duplicate problem code within one sheet
 * @param invalidRows                 rows dropped for any other reason (missing code/title, an
 *                                    in-workbook roadmap slot conflict, a Topics row with no match)
 * @param recognizedSheets           sheet names the import actually reads from
 * @param ignoredSheets               sheet names present in the workbook but never read (unrecognized
 *                                    extra sheets, or a roadmap sheet with no usable code/title columns)
 * @param missingSheets                roadmap stage sheets not present in the workbook at all
 */
public record WorkbookPreviewDetails(int uniqueProblemCount, int roadmapMembershipCount,
                                     Map<RoadmapStage, Integer> stageMembershipCounts, int hyperlinksFound,
                                     int hyperlinksMissing, Map<String, Integer> platformCounts,
                                     int explicitPlatformCount, int inferredPlatformCount, int unknownPlatformCount,
                                     int solvedCount, int inProgressCount, int revisitCount, int notStartedCount,
                                     Map<String, Integer> topicCounts, int qualityMetadataCount,
                                     int suggestedLevelMetadataCount, int assistanceMetadataCount,
                                     Map<String, Integer> rowsSkippedByReason, int duplicateRowsSkipped, int invalidRows,
                                     List<String> recognizedSheets, List<String> ignoredSheets, List<String> missingSheets) {
}
