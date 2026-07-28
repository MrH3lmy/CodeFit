package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ImportBatch;
import com.codefit.model.Problem;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ImportBatchRepository;
import com.codefit.repository.ProblemRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #160 gap analysis findings against the previous rollback-based preview: analysis must
 * be pure (no database access at all, not "write then roll back"), and a confirmed import must consume
 * the exact {@link AnalyzedTrainingWorkbook} the learner reviewed rather than re-reading the file.
 */
class TrainingSheetAnalyzerTest {

    private final TrainingSheetImportService importService = new TrainingSheetImportService();
    private final ProblemRepository problemRepository = new ProblemRepository();
    private final ImportBatchRepository importBatchRepository = new ImportBatchRepository();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private final Random random = new Random();
    private int nextOrder = 20_000_000 + random.nextInt(1_000_000);

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-ANALYZE-" + testName + "-" + UUID.randomUUID();
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
    void analysisNeverWritesToTheDatabase(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("no-writes");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-writes.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));
        int problemCountBefore = problemRepository.countAll();
        int batchCountBefore = importBatchRepository.findAll().size();

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertEquals(1, analyzed.details().uniqueProblemCount());
        assertEquals(problemCountBefore, problemRepository.countAll(), "analysis must not insert a problem");
        assertEquals(batchCountBefore, importBatchRepository.findAll().size(), "analysis must not create an import batch");
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isEmpty(),
                "no problem row exists, so no roadmap membership/progress/attempt could exist for it either");
    }

    @Test
    void cancellingAfterAnalysisLeavesTheDatabaseUnchanged(@TempDir Path tempDir) throws Exception {
        // "Cancel" is simply never calling importAnalyzed() after analyze() - there is no separate
        // rollback step to forget, because analysis was never inside a transaction to begin with.
        String platform = uniquePlatform("cancelled");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "cancelled.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        importService.analyze(workbookPath);
        importService.analyze(workbookPath); // analyzing repeatedly (re-opening the review dialog) is still a no-op

        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isEmpty());
    }

    @Test
    void previewAndImportConsumeTheExactSameAnalyzedTrainingWorkbook(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("shared-model");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "shared-model.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);
        TrainingSheetImportSummary preview = importService.previewOf(analyzed);
        TrainingSheetImportSummary imported = importService.importAnalyzed(analyzed, ImportSourceMetadata.unspecified());

        assertSame(analyzed.details(), preview.details(), "preview must render the analyzed model, not a fresh copy");
        assertSame(analyzed.details(), imported.details(), "import must report the exact same analyzed content the preview showed");
        assertEquals(preview.details().uniqueProblemCount(), imported.details().uniqueProblemCount());
        assertEquals(preview.details().roadmapMembershipCount(), imported.details().roadmapMembershipCount());
    }

    @Test
    void importDoesNotReReadOrDependOnTheOriginalFileAfterAnalysis(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("no-reread");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-reread.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);
        Files.delete(workbookPath); // the file is gone; importAnalyzed must not need to read it again

        TrainingSheetImportSummary summary = importService.importAnalyzed(analyzed, ImportSourceMetadata.unspecified());

        assertFalse(summary.dryRun());
        assertEquals(1, summary.problemsCreated());
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isPresent(),
                "the import succeeded from the already-analyzed snapshot even though the source file no longer exists");
    }

    @Test
    void changingTheWorkbookFileAfterAnalysisCannotChangeTheConfirmedImport(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("no-drift");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-drift.xlsx", List.of(
                sheet("A", List.of(row("ORIGINAL", "Original Problem", platform, "")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        // Overwrite the same path with completely different content, simulating the learner editing
        // or replacing the file while the review dialog is still open.
        Files.delete(workbookPath);
        TrainingSheetFixtures.writeWorkbook(tempDir, "no-drift.xlsx", List.of(
                sheet("A", List.of(row("REPLACED", "Replaced Problem", platform, "")))));

        TrainingSheetImportSummary summary = importService.importAnalyzed(analyzed, ImportSourceMetadata.unspecified());

        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "ORIGINAL").isPresent(),
                "the originally-analyzed problem is the one that got imported");
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "REPLACED").isEmpty(),
                "the file's post-analysis replacement content must never reach the database");
        assertEquals(1, summary.problemsCreated());
    }

    @Test
    void allSevenStagesAppearInThePreviewIncludingZeroCountOnes(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("zero-stages");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "zero-stages.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        for (RoadmapStage stage : RoadmapStage.values()) {
            assertTrue(analyzed.details().stageMembershipCounts().containsKey(stage), stage + " must be present even with zero entries");
        }
        assertEquals(1, analyzed.details().stageMembershipCounts().get(RoadmapStage.A));
        assertEquals(0, analyzed.details().stageMembershipCounts().get(RoadmapStage.B));
    }

    @Test
    void notStartedCountIsComputedFromRowsWithNoRecognizedAdvancingStatus(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("not-started");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "not-started.xlsx", List.of(
                sheet("A", List.of(
                        row("P1", "Solved", platform, "AC"),
                        row("P2", "Blank status", platform, ""),
                        row("P3", "Unstarted token", platform, "Not Started")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertEquals(1, analyzed.details().solvedCount());
        assertEquals(2, analyzed.details().notStartedCount());
        assertEquals(3, analyzed.details().roadmapMembershipCount());
    }

    @Test
    void platformSourceIsClassifiedAsExplicitInferredOrUnknown(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("platform-source");
        TrainingSheetFixtures.SheetSpec sheetWithoutPlatformColumn = new TrainingSheetFixtures.SheetSpec("A",
                List.of("Code", "Title", "Platform", "Order"),
                List.of(
                        List.of("EXP1", "Explicit Platform", platform, String.valueOf(nextOrder++)),
                        List.of("CF999-D2-A", "Inferred From Code Prefix", "", String.valueOf(nextOrder++)),
                        List.of("UNRECOGNIZABLE-CODE-XYZ", "Unknown Platform", "", String.valueOf(nextOrder++))));
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "platform-source.xlsx", List.of(sheetWithoutPlatformColumn));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertEquals(1, analyzed.details().explicitPlatformCount());
        assertEquals(1, analyzed.details().inferredPlatformCount());
        assertEquals(1, analyzed.details().unknownPlatformCount());
        assertTrue(analyzed.details().platformCounts().containsKey("Codeforces"));
    }

    @Test
    void suggestedLevelQualityAndAssistanceCoverageAreCountedIndependently(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("coverage");
        TrainingSheetFixtures.SheetSpec sheetSpec = new TrainingSheetFixtures.SheetSpec("A",
                List.of("Code", "Title", "Platform", "Level", "Quality", "By yourself?", "Order"),
                List.of(
                        List.of("P1", "Full metadata", platform, "Easy", "5", "Yes", String.valueOf(nextOrder++)),
                        List.of("P2", "No metadata", platform, "", "", "", String.valueOf(nextOrder++))));
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "coverage.xlsx", List.of(sheetSpec));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertEquals(1, analyzed.details().suggestedLevelMetadataCount());
        assertEquals(1, analyzed.details().qualityMetadataCount());
        assertEquals(1, analyzed.details().assistanceMetadataCount());
        assertEquals(2, analyzed.details().roadmapMembershipCount());
    }

    @Test
    void diagnosticsCarrySeverityAndBlockingNeverComesFromASingleBadRow(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("warning-only");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "warning-only.xlsx", List.of(
                sheet("A", List.of(
                        row("P1", "Two Sum", platform, ""),
                        row("P1", "Two Sum (accidental duplicate)", platform, "")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertFalse(analyzed.hasBlockingDiagnostics(), "a skipped duplicate row must never block the rest of an otherwise-importable workbook");
        assertTrue(analyzed.diagnostics().stream().allMatch(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.WARNING));

        TrainingSheetImportSummary preview = importService.previewOf(analyzed);
        assertFalse(preview.hasBlockingDiagnostics());
    }

    @Test
    void aWorkbookWithNoUsableSheetProducesABlockingDiagnostic(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "blocking.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("RandomNotes", List.of("Comment"), List.of(List.of("nothing useful here")))));

        AnalyzedTrainingWorkbook analyzed = importService.analyze(workbookPath);

        assertTrue(analyzed.hasBlockingDiagnostics());
        assertTrue(analyzed.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.BLOCKING));
        assertEquals(0, analyzed.details().uniqueProblemCount());
        assertEquals(0, analyzed.details().roadmapMembershipCount());
    }

    @Test
    void reImportingTheSameWorkbookStillReportsTheSameStableContentCounts(@TempDir Path tempDir) throws Exception {
        // The core #160 complaint: a preview's workbook-content counts must not degrade just because
        // the workbook has already been imported once before.
        String platform = uniquePlatform("stable-counts");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "stable-counts.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC"), row("P2", "Three Sum", platform, "")))));

        AnalyzedTrainingWorkbook beforeImport = importService.analyze(workbookPath);
        importService.importAnalyzed(beforeImport, ImportSourceMetadata.unspecified());
        AnalyzedTrainingWorkbook afterImport = importService.analyze(workbookPath);

        assertEquals(beforeImport.details().uniqueProblemCount(), afterImport.details().uniqueProblemCount());
        assertEquals(beforeImport.details().roadmapMembershipCount(), afterImport.details().roadmapMembershipCount());
        assertEquals(2, afterImport.details().uniqueProblemCount());
        assertEquals(2, afterImport.details().roadmapMembershipCount());
    }
}
