package com.codefit.service;

import com.codefit.model.RoadmapStage;

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

        report.append("Recognized sheets: ").append(joinOrNone(details.recognizedSheets())).append('\n');
        report.append("Ignored sheets: ").append(joinOrNone(details.ignoredSheets())).append('\n');
        report.append("Missing sheets: ").append(joinOrNone(details.missingSheets())).append('\n');
        report.append('\n');

        report.append("Per-stage roadmap memberships (").append(totalMemberships(details)).append(" total):\n");
        for (RoadmapStage stage : RoadmapStage.values()) {
            int count = details.stageMembershipCounts().getOrDefault(stage, 0);
            if (count > 0) {
                report.append("  ").append(stage.name()).append(": ").append(count).append('\n');
            }
        }
        report.append('\n');

        report.append("Problems: ").append(summary.problemsCreated()).append(" created, ")
                .append(summary.problemsUpdated()).append(" updated, ")
                .append(summary.problemsReused()).append(" reused\n");
        report.append("Progress: ").append(summary.progressRecordsImported()).append(" state change(s) — ")
                .append(details.solvedCount()).append(" solved, ")
                .append(details.inProgressCount()).append(" in progress, ")
                .append(details.revisitCount()).append(" needs revisit\n");
        report.append("Attempt snapshots imported: ").append(summary.attemptsImported()).append('\n');
        report.append("Reflection fields imported: ").append(summary.reflectionFieldsImported()).append('\n');
        report.append("Judge links: ").append(details.hyperlinksFound()).append(" found, ")
                .append(details.hyperlinksMissing()).append(" missing\n");
        report.append("Quality metadata found on ").append(details.qualityMetadataCount()).append(" row(s)\n");
        report.append('\n');

        appendCountMap(report, "Platforms", details.platformCounts());
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

    private static int totalMemberships(WorkbookPreviewDetails details) {
        return details.stageMembershipCounts().values().stream().mapToInt(Integer::intValue).sum();
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
