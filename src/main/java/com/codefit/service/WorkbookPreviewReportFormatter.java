package com.codefit.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TrainingSheetImportSummary} into the plain-text diagnostic report a learner sees
 * on the import "review and confirm" screen (#160), and can copy/export as-is. Pure text formatting,
 * deliberately kept free of any JavaFX dependency so it's usable — and testable — without a UI
 * toolkit.
 */
public final class WorkbookPreviewReportFormatter {

    private WorkbookPreviewReportFormatter() {
    }

    public static String format(String workbookFileName, TrainingSheetImportSummary summary) {
        WorkbookPreviewDetails details = summary.details();
        StringBuilder report = new StringBuilder();
        report.append(summary.dryRun() ? "PREVIEW — nothing has been written to the database yet" : "IMPORT COMPLETE")
                .append('\n')
                .append("Workbook: ").append(workbookFileName).append('\n');
        if (summary.hasBlockingDiagnostics()) {
            report.append("BLOCKING ERRORS FOUND — nothing can be imported until these are resolved.\n");
        }
        report.append('\n');

        report.append("Profile: ").append(details.profile().name()).append('\n');
        report.append("Version: ").append(details.profile().version()).append('\n');
        report.append('\n');

        report.append("Unique problems: ").append(details.uniqueProblemCount()).append('\n');
        report.append("Roadmap memberships: ").append(details.roadmapMembershipCount()).append('\n');
        report.append('\n');

        report.append("Recognized sheets: ").append(joinOrNone(details.recognizedSheets())).append('\n');
        report.append("Ignored sheets: ").append(joinOrNone(details.ignoredSheets())).append('\n');
        report.append("Missing sheets: ").append(joinOrNone(details.missingSheets())).append('\n');
        report.append('\n');

        report.append("Per-stage rows (detected / valid / skipped):\n");
        for (TrainingSheetStageSummary stageSummary : details.stageSummaries()) {
            report.append("  ").append(stageSummary.stage().name()).append(": ")
                    .append(stageSummary.detectedRows()).append(" / ")
                    .append(stageSummary.validRows()).append(" / ")
                    .append(stageSummary.skippedRows()).append('\n');
        }
        report.append('\n');

        report.append("Workbook progress: ").append(details.solvedCount()).append(" solved, ")
                .append(details.inProgressCount()).append(" in progress, ")
                .append(details.revisitCount()).append(" needs revisit, ")
                .append(details.notStartedCount()).append(" not started\n");
        if (summary.dryRun()) {
            // #160: previewOf() never opens a database connection, so it cannot know whether a
            // problem would be created vs. reused - reporting those fields as "0" here would read as
            // "zero problems will be imported", which is actively misleading for a workbook that has
            // plenty to import. Show the workbook's own content counts instead, and say plainly that
            // the database effect itself is only known after confirmation.
            report.append("Database effect: evaluated only after confirmation\n");
            report.append("Attempt snapshots found in workbook: ").append(details.attemptSnapshotsFound()).append(" problem(s)\n");
            report.append("Problems with reflection metadata: ").append(details.problemsWithReflectionMetadata()).append(" problem(s)\n");
        } else {
            report.append("Database effect: ").append(summary.problemsCreated()).append(" problem(s) created, ")
                    .append(summary.problemsUpdated()).append(" updated, ")
                    .append(summary.problemsReused()).append(" reused; ")
                    .append(summary.roadmapMembershipsCreated()).append(" new roadmap membership(s); ")
                    .append(summary.progressRecordsImported()).append(" progress state change(s)\n");
            report.append("Attempt snapshots imported: ").append(summary.attemptsImported()).append('\n');
            report.append("Reflection fields imported: ").append(summary.reflectionFieldsImported()).append('\n');
        }
        report.append('\n');

        report.append("Judge links: ").append(details.hyperlinksFound()).append(" found, ")
                .append(details.hyperlinksMissing()).append(" missing\n");
        report.append("Platforms: ").append(details.explicitPlatformCount()).append(" explicit, ")
                .append(details.inferredPlatformCount()).append(" inferred, ")
                .append(details.unknownPlatformCount()).append(" unknown\n");
        report.append("Suggested-level coverage: ").append(details.suggestedLevelMetadataCount()).append(" row(s)\n");
        report.append("Quality metadata coverage: ").append(details.qualityMetadataCount()).append(" row(s)\n");
        report.append("Assistance/independence coverage: ").append(details.assistanceMetadataCount()).append(" row(s)\n");
        report.append('\n');

        appendCountMap(report, "Platforms breakdown", details.platformCounts());
        appendCountMap(report, "Topics", details.topicCounts());
        appendCountMap(report, "Rows skipped", details.rowsSkippedByReason());

        report.append("Duplicate rows skipped: ").append(summary.duplicateRowsSkipped()).append('\n');
        report.append("Invalid rows skipped: ").append(summary.invalidRows()).append('\n');

        List<TrainingSheetDiagnostic> blocking = summary.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.BLOCKING).toList();
        List<TrainingSheetDiagnostic> warnings = summary.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.WARNING).toList();
        if (!blocking.isEmpty()) {
            report.append('\n').append("Blocking errors (").append(blocking.size()).append("):\n");
            blocking.forEach(diagnostic -> report.append("  - ").append(diagnostic.describe()).append('\n'));
        }
        if (!warnings.isEmpty()) {
            report.append('\n').append("Warnings (").append(warnings.size()).append("):\n");
            warnings.forEach(diagnostic -> report.append("  - ").append(diagnostic.describe()).append('\n'));
        }
        return report.toString();
    }

    private static String joinOrNone(List<String> sheetNames) {
        return sheetNames.isEmpty() ? "none" : String.join(", ", sheetNames);
    }

    private static void appendCountMap(StringBuilder report, String label, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return;
        }
        report.append(label).append(":\n");
        counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(entry -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> report.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        report.append('\n');
    }
}
