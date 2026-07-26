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
        progressService.updateProgress(problem.getId(), ProblemState.IN_PROGRESS, null, null, null,
                "learner already started this", null, null);

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
        String platform = uniquePlatform("dry-run");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "dry-run.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "AC")))));

        TrainingSheetImportSummary preview = importService.preview(workbookPath);

        assertTrue(preview.dryRun());
        assertEquals(1, preview.problemsCreated());
        assertEquals(1, preview.roadmapMembershipsCreated());
        assertTrue(problemRepository.findByPlatformAndExternalCode(platform, "P1").isEmpty(),
                "a dry-run preview must not write anything to the database");

        TrainingSheetImportSummary realImport = importService.importWorkbook(workbookPath);
        assertEquals(preview.problemsCreated(), realImport.problemsCreated());
        assertEquals(preview.roadmapMembershipsCreated(), realImport.roadmapMembershipsCreated());
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
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.contains("already held by")));
    }
}
