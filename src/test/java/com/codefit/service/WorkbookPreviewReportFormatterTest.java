package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the review finding that a pure preview's zeroed-out database-effect fields
 * ({@code problemsCreated}, {@code attemptsImported}, etc. - see {@link TrainingSheetImportService#previewOf})
 * must never be printed as if they were real results (#160): a preview hasn't evaluated the database
 * effect at all, so saying "0 problem(s) created" or "Attempt snapshots imported: 0" for a workbook
 * that plainly has importable content would be actively misleading.
 */
class WorkbookPreviewReportFormatterTest {

    private final TrainingSheetImportService importService = new TrainingSheetImportService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private final Random random = new Random();
    private int nextOrder = 30_000_000 + random.nextInt(1_000_000);

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-FORMATTER-" + testName + "-" + UUID.randomUUID();
    }

    private TrainingSheetFixtures.SheetSpec sheet(String name, List<List<String>> rows) {
        return new TrainingSheetFixtures.SheetSpec(name,
                List.of("Code", "Title", "Platform", "Link", "Set", "Mandatory", "Level", "Topic", "Quality", "Status", "Order"),
                rows);
    }

    private List<String> row(String code, String title, String platform, String status) {
        return List.of(code, title, platform, "https://example.test/" + code, "1", "Yes", "Easy", "Arrays", "4",
                status == null ? "" : status, String.valueOf(nextOrder++));
    }

    @Test
    void previewNeverClaimsZeroProblemsOrMembershipsWillBeImported(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("no-misleading-zero");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-misleading-zero.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC"), row("P2", "Three Sum", platform, "")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);
        TrainingSheetImportSummary preview = importService.previewOf(analyzed);
        String report = WorkbookPreviewReportFormatter.format("no-misleading-zero.xlsx", preview);

        assertTrue(preview.dryRun());
        assertFalse(report.contains("0 problem(s) created"), "a preview must never claim zero problems will be created");
        assertFalse(report.contains("Attempt snapshots imported: 0"),
                "a preview never imports anything, so it must not claim zero attempts were imported");
        assertFalse(report.contains("Reflection fields imported: 0"),
                "a preview never imports anything, so it must not claim zero reflection fields were imported");
        assertTrue(report.contains("Database effect: evaluated only after confirmation"),
                "the report must say the database effect is unknown, not print zeros for it");
        assertTrue(report.contains("Unique problems: 2"), "the workbook's own stable content counts must still be shown");
    }

    @Test
    void previewShowsWorkbookContentAttemptAndReflectionCoverageInsteadOfImportedCounts(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("attempt-reflection-coverage");
        TrainingSheetFixtures.SheetSpec sheetSpec = new TrainingSheetFixtures.SheetSpec("A",
                List.of("Code", "Title", "Platform", "Status", "Problem Level /10", "Order"),
                List.of(List.of("P1", "Two Sum", platform, "AC", "7", String.valueOf(nextOrder++))));
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "attempt-reflection-coverage.xlsx", List.of(sheetSpec));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);
        TrainingSheetImportSummary preview = importService.previewOf(analyzed);
        String report = WorkbookPreviewReportFormatter.format("attempt-reflection-coverage.xlsx", preview);

        assertTrue(report.contains("Attempt snapshots found in workbook: 1"));
        assertTrue(report.contains("Problems with reflection metadata: 1"));
    }

    @Test
    void completedImportReportShowsActualDatabaseEffectNotWorkbookContentLabels(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("real-import-report");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "real-import-report.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);
        String report = WorkbookPreviewReportFormatter.format("real-import-report.xlsx", summary);

        assertFalse(summary.dryRun());
        assertTrue(report.contains("1 problem(s) created"));
        assertFalse(report.contains("Database effect: evaluated only after confirmation"));
        assertFalse(report.contains("Attempt snapshots found in workbook"), "a completed import reports actual imported counts, not workbook-content labels");
    }
}
