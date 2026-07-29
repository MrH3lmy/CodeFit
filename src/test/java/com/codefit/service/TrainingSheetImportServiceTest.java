package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #143 acceptance criteria against synthetic, programmatically-built {@code .xlsx}
 * fixtures (never the real Junior Training Sheet): each sheet, duplicate handling, malformed rows,
 * progress preservation, and repeat import. Touches the shared local database the same way
 * {@code AssessmentIsolationTest}/{@code ProblemServiceTest} do, using a fresh unique platform label
 * per test method so repeated test runs never collide with each other's fixture data.
 */
class TrainingSheetImportServiceTest {

    private final TrainingSheetImportService importService = new TrainingSheetImportService();
    private final ProblemRepository problemRepository = new ProblemRepository();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressRepository progressRepository = new ProblemProgressRepository();
    private final ProblemProgressService progressService = new ProblemProgressService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    // The roadmap_entries table's (stage, sequence_order) slot is a single global position shared
    // with every other test touching this database (e.g. ProblemServiceTest's fixed stage-A
    // position 1). Every row this test writes gets an explicit, randomly-based "Order" value so it
    // can never collide with another test's fixture data or with a previous run of this same test.
    private final Random random = new Random();
    private int nextOrder = 10_000_000 + random.nextInt(1_000_000);

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-IMPORT-" + testName + "-" + UUID.randomUUID();
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
    void firstImportCreatesProblemsMembershipsAndAppliesSolvedStatus(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("first-import");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "first-import.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        assertFalse(summary.dryRun());
        assertEquals(1, summary.problemsCreated());
        assertEquals(1, summary.roadmapMembershipsCreated());
        assertEquals(1, summary.progressRecordsImported());

        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        assertEquals("Two Sum", problem.getTitle());
        assertEquals("Arrays", problem.getTopic());
        assertEquals(4, problem.getQualityRating());

        List<RoadmapEntry> entries = roadmapEntryRepository.findByProblemId(problem.getId());
        assertEquals(1, entries.size());
        assertEquals(RoadmapStage.A, entries.get(0).getStage());
        assertEquals(DifficultyLevel.EASY, entries.get(0).getSuggestedLevel());

        ProblemProgress progress = progressRepository.findByProblemId(problem.getId()).orElseThrow();
        assertEquals(ProblemState.SOLVED, progress.getState());

        WorkbookPreviewDetails details = summary.details();
        assertEquals(1, details.stageMembershipCounts().get(RoadmapStage.A));
        assertEquals(1, details.solvedCount());
        assertEquals(1, details.hyperlinksFound(), "the row's explicit Link column resolves as a found hyperlink");
        assertEquals(1, details.platformCounts().get(platform));
        assertEquals(1, details.qualityMetadataCount());

        String report = WorkbookPreviewReportFormatter.format("first-import.xlsx", summary);
        assertTrue(report.contains("A: 1"), "the report shows per-stage counts");
        assertTrue(report.contains(platform), "the report lists the inferred/explicit platform");
    }

    @Test
    void theThreeRepeatedProblemCodesAreRepresentedAsUniqueProblemsWithMultipleMemberships(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("repeated-codes");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "repeated-codes.xlsx", List.of(
                sheet("A", List.of(
                        row("R1", "Repeated One", platform, ""),
                        row("R2", "Repeated Two", platform, ""),
                        row("R3", "Repeated Three", platform, ""))),
                sheet("C2", List.of(
                        row("R1", "Repeated One", platform, ""),
                        row("R2", "Repeated Two", platform, ""),
                        row("R3", "Repeated Three", platform, "")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        assertEquals(3, summary.problemsCreated());
        assertEquals(6, summary.roadmapMembershipsCreated());

        for (String code : List.of("R1", "R2", "R3")) {
            Problem problem = problemRepository.findByPlatformAndExternalCode(platform, code).orElseThrow();
            List<RoadmapEntry> entries = roadmapEntryRepository.findByProblemId(problem.getId());
            assertEquals(2, entries.size(), code + " should have exactly two roadmap memberships");
            assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.A));
            assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.C2));
        }
    }

    @Test
    void secondIdenticalImportProducesNoDuplicateData(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("repeat-import");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "repeat-import.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        TrainingSheetImportSummary first = importService.importWorkbook(workbookPath);
        TrainingSheetImportSummary second = importService.importWorkbook(workbookPath);

        assertEquals(1, first.problemsCreated());
        assertEquals(0, second.problemsCreated());
        assertEquals(0, second.roadmapMembershipsCreated());
        assertEquals(1, second.problemsReused());
        assertEquals(0, second.progressRecordsImported(), "status was already applied by the first import");

        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        assertEquals(1, roadmapEntryRepository.findByProblemId(problem.getId()).size());
    }

    @Test
    void malformedRowsMissingCodeOrTitleAreSkippedAndCounted(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("malformed-rows");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "malformed-rows.xlsx", List.of(
                sheet("A", List.of(
                        row("P1", "Valid Problem", platform, ""),
                        List.of("", "Missing Code", platform, "", "", "", "", "", "", "", String.valueOf(nextOrder++)),
                        List.of("P2", "", platform, "", "", "", "", "", "", "", String.valueOf(nextOrder++))))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        assertEquals(1, summary.problemsCreated());
        assertEquals(2, summary.invalidRows());
        assertFalse(summary.warnings().isEmpty());
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P2").isEmpty());
    }

    @Test
    void duplicateRowsWithinTheSameSheetAreSkipped(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("duplicate-rows");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "duplicate-rows.xlsx", List.of(
                sheet("A", List.of(
                        row("P1", "Two Sum", platform, ""),
                        row("P1", "Two Sum (accidental copy-paste)", platform, "")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        assertEquals(1, summary.problemsCreated());
        assertEquals(1, summary.duplicateRowsSkipped());
        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        assertEquals(1, roadmapEntryRepository.findByProblemId(problem.getId()).size());
    }

    @Test
    void topicsSheetAppliesTopicMetadataWithoutCreatingNewProblems(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("topics-sheet");
        TrainingSheetFixtures.SheetSpec topicsSheet = new TrainingSheetFixtures.SheetSpec("Topics",
                List.of("Code", "Platform", "Topic"),
                List.of(
                        List.of("P1", platform, "Hash Table"),
                        List.of("UNKNOWN-CODE", platform, "Graphs")));

        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "topics-sheet.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, ""))),
                topicsSheet));

        int problemCountBefore = problemRepository.countAll();
        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        assertEquals("Hash Table", problem.getTopic());
        assertEquals(problemCountBefore + 1, problemRepository.countAll(), "the unknown Topics code must not create a new problem");
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.contains("UNKNOWN-CODE")));
    }

    @Test
    void existingProgressIsNeverDowngradedByReImport(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("progress-preserved");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "progress-preserved.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        importService.importWorkbook(workbookPath);
        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        progressService.updateProgress(problem.getId(), ProblemState.IN_PROGRESS, null);
        progressService.updateReflection(problem.getId(), new ProblemReflection(null, null, null,
                "learner already started this", null, null, null, null, null, null, false, false, false, false));

        Path secondWorkbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "progress-preserved-2.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));
        TrainingSheetImportSummary summary = importService.importWorkbook(secondWorkbookPath);

        assertEquals(0, summary.progressRecordsImported());
        ProblemProgress progress = progressRepository.findByProblemId(problem.getId()).orElseThrow();
        assertEquals(ProblemState.IN_PROGRESS, progress.getState());
        assertEquals("learner already started this", progress.getApproachNotes());
    }

    @Test
    void dryRunPreviewComputesTheSummaryButWritesNothing(@TempDir Path tempDir) throws Exception {
        // #160: preview() is pure analysis - it never opens a database connection at all, so it can't
        // (and shouldn't) report database-dependent created/updated/reused counts; those are only
        // meaningful once a real import runs. What preview() reports instead are the workbook's own
        // stable content counts, which are identical before and after the workbook has ever been
        // imported (see AnalyzedTrainingWorkbookTest for that non-degradation guarantee).
        String platform = uniquePlatform("dry-run");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "dry-run.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        TrainingSheetImportSummary preview = importService.preview(workbookPath);

        assertTrue(preview.dryRun());
        assertEquals(0, preview.problemsCreated(), "a pure analysis never touches the database, so it can't know created-vs-reused");
        assertEquals(0, preview.roadmapMembershipsCreated());
        assertEquals(1, preview.details().uniqueProblemCount());
        assertEquals(1, preview.details().roadmapMembershipCount());
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isEmpty(),
                "a dry-run preview must not write anything to the database");

        TrainingSheetImportSummary realImport = importService.importWorkbook(workbookPath);
        assertEquals(1, realImport.problemsCreated());
        assertEquals(1, realImport.roadmapMembershipsCreated());
        assertEquals(preview.details().uniqueProblemCount(), realImport.details().uniqueProblemCount());
        assertEquals(preview.details().roadmapMembershipCount(), realImport.details().roadmapMembershipCount());
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isPresent());
    }

    @Test
    void workbookWithNoUsableRoadmapSheetFailsValidationAndIsRejectedBeforeWritingAnything(@TempDir Path tempDir) throws Exception {
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-roadmap-sheets.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("RandomNotes", List.of("Comment"), List.of(List.of("nothing useful here")))));

        WorkbookValidationResult validation = importService.validate(workbookPath);
        assertFalse(validation.valid());

        assertThrows(WorkbookImportException.class, () -> importService.importWorkbook(workbookPath));
    }

    @Test
    void validateReportsMissingStageSheetsAsWarningsWhileStayingValid(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("partial-workbook");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "partial-workbook.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        WorkbookValidationResult validation = importService.validate(workbookPath);

        assertTrue(validation.valid());
        assertTrue(validation.missingRoadmapSheets().containsAll(
                List.of("B", "C1", "C2", "D1", "D2", "D3")));
    }

    @Test
    void conflictingExplicitOrderWithinASheetIsCaughtAsAnInvalidRowNotACrash(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("order-conflict");
        String contestedOrder = String.valueOf(nextOrder++);
        TrainingSheetFixtures.SheetSpec sheetWithOrder = new TrainingSheetFixtures.SheetSpec("A",
                List.of("Code", "Title", "Platform", "Order"),
                List.of(
                        List.of("P1", "First Problem", platform, contestedOrder),
                        List.of("P2", "Second Problem", platform, contestedOrder)));
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "order-conflict.xlsx", List.of(sheetWithOrder));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        assertEquals(2, summary.problemsCreated(), "both problems are still created even though one membership conflicts");
        assertEquals(1, summary.roadmapMembershipsCreated());
        assertEquals(1, summary.invalidRows());
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.contains("already claimed by another problem")));
    }

    @Test
    void previewOfAnUnusableWorkbookReturnsABlockedSummaryInsteadOfThrowing(@TempDir Path tempDir) throws Exception {
        // #160: the review screen must be able to show *something* for a completely unusable
        // workbook - a BLOCKING diagnostic with zero counts - rather than the caller only getting a
        // thrown exception with no structured report to render. importWorkbook() still refuses outright.
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-roadmap-sheets-preview.xlsx", List.of(
                new TrainingSheetFixtures.SheetSpec("RandomNotes", List.of("Comment"), List.of(List.of("nothing useful here")))));

        TrainingSheetImportSummary preview = importService.preview(workbookPath);

        assertTrue(preview.dryRun());
        assertTrue(preview.hasBlockingDiagnostics());
        assertEquals(0, preview.problemsCreated());
        assertEquals(0, preview.roadmapMembershipsCreated());
        assertTrue(preview.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.severity() == com.codefit.service.TrainingSheetDiagnosticSeverity.BLOCKING));
        assertTrue(preview.details().ignoredSheets().contains("RandomNotes"),
                "the unrecognized extra sheet is reported as ignored, not silently dropped");

        String report = WorkbookPreviewReportFormatter.format("no-roadmap-sheets-preview.xlsx", preview);
        assertTrue(report.contains("BLOCKING ERRORS FOUND"));

        assertThrows(WorkbookImportException.class, () -> importService.importWorkbook(workbookPath),
                "a real import must still refuse a workbook with nothing importable");
    }

    @Test
    void aValidImportHasNoBlockingDiagnostics(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("no-blocking");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "no-blocking.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        TrainingSheetImportSummary summary = importService.preview(workbookPath);

        assertFalse(summary.hasBlockingDiagnostics());
    }

    @Test
    void rowLevelDiagnosticsCarryStructuredSheetRowAndColumnContext(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("structured-diagnostics");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "structured-diagnostics.xlsx", List.of(
                sheet("A", List.of(
                        row("P1", "Two Sum", platform, ""),
                        row("P1", "Two Sum (accidental copy-paste)", platform, "")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        com.codefit.service.TrainingSheetDiagnostic duplicateDiagnostic = summary.diagnostics().stream()
                .filter(diagnostic -> "Code".equals(diagnostic.column()) && diagnostic.reason().contains("duplicate problem code"))
                .findFirst().orElseThrow(() -> new AssertionError("expected a structured duplicate-code diagnostic"));

        assertEquals("A", duplicateDiagnostic.sheet());
        assertEquals(3, duplicateDiagnostic.row(), "the second (duplicate) row is spreadsheet row 3 (header is row 1)");
        assertEquals(com.codefit.service.TrainingSheetDiagnosticSeverity.WARNING, duplicateDiagnostic.severity(),
                "one skipped row never blocks the rest of the import");
    }

    @Test
    void validateReportsRecognizedIgnoredAndMissingSheets(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("sheet-lists");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "sheet-lists.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, ""))),
                new TrainingSheetFixtures.SheetSpec("Extra Notes", List.of("Comment"), List.of(List.of("not a roadmap sheet")))));

        WorkbookValidationResult validation = importService.validate(workbookPath);

        assertTrue(validation.recognizedSheets().contains("A"));
        assertTrue(validation.ignoredSheets().contains("Extra Notes"));
        assertTrue(validation.missingRoadmapSheets().containsAll(List.of("B", "C1", "C2", "D1", "D2", "D3")));
    }
}
