package com.codefit.service;

import com.codefit.model.ImportBatch;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ImportBatchRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies source attribution and safe deletion of imported roadmaps (#149): every import records an
 * {@link ImportBatch} with the given source metadata, every {@link RoadmapEntry} it creates is
 * stamped with that batch's id, and deleting a batch removes exactly its roadmap positions — never
 * the underlying problem catalog, progress, attempts, or flashcards. Uses the same synthetic,
 * programmatically-built {@code .xlsx} fixtures as {@link TrainingSheetImportServiceTest}.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ImportSourceAttributionTest {

    private final TrainingSheetImportService importService = new TrainingSheetImportService();
    private final ProblemRepository problemRepository = new ProblemRepository();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressRepository progressRepository = new ProblemProgressRepository();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();
    private final ImportBatchRepository importBatchRepository = new ImportBatchRepository();
    private final ProblemFlashcardService problemFlashcardService = new ProblemFlashcardService();

    private static final AtomicInteger ROADMAP_ORDER_SEQUENCE = new AtomicInteger(1);
    private int nextOrder = ROADMAP_ORDER_SEQUENCE.getAndAdd(1_000);

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-ATTRIBUTION-" + testName + "-" + UUID.randomUUID();
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
    void importedSourceMetadataIsRecordedOnTheBatch(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("metadata");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "metadata.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        ImportSourceMetadata metadata = new ImportSourceMetadata("Test Curriculum", "https://example.test/curriculum", "Test Author", "2.1");
        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath, metadata);

        ImportBatch batch = importBatchRepository.findById(summary.importBatchId()).orElseThrow();
        assertEquals("Test Curriculum", batch.getSourceName());
        assertEquals("https://example.test/curriculum", batch.getSourceUrl());
        assertEquals("Test Author", batch.getAuthor());
        assertEquals("2.1", batch.getVersion());
    }

    @Test
    void blankSourceNameFallsBackToTheWorkbookFileName(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("blank-name");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "my-roadmap-file.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath, ImportSourceMetadata.unspecified());

        ImportBatch batch = importBatchRepository.findById(summary.importBatchId()).orElseThrow();
        assertEquals("my-roadmap-file.xlsx", batch.getSourceName());
    }

    @Test
    void everyRoadmapEntryCreatedByAnImportIsStampedWithItsBatchId(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("stamped-entries");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "stamped.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, ""), row("P2", "Three Sum", platform, "")))));

        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);

        List<RoadmapEntry> entries = roadmapEntryRepository.findByImportBatchId(summary.importBatchId());
        assertEquals(2, entries.size());
    }

    @Test
    void dryRunPreviewNeverDurablyCreatesAnImportBatch(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("dry-run-batch");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "dry-run-batch.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));
        int batchCountBefore = importBatchRepository.findAll().size();

        TrainingSheetImportSummary preview = importService.preview(workbookPath);

        assertNull(preview.importBatchId(), "a rolled-back dry run must not report a durable batch id");
        assertEquals(batchCountBefore, importBatchRepository.findAll().size());
    }

    @Test
    void deletingAnImportBatchRemovesOnlyItsRoadmapEntriesAndTheBatchItself(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("delete-batch");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "delete-batch.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, ""), row("P2", "Three Sum", platform, "")))));
        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);
        long batchId = summary.importBatchId();

        int removed = importService.deleteImportBatch(batchId);

        assertEquals(2, removed);
        assertTrue(roadmapEntryRepository.findByImportBatchId(batchId).isEmpty());
        assertTrue(importBatchRepository.findById(batchId).isEmpty());
    }

    @Test
    void deletingAnImportBatchNeverTouchesProblemsProgressAttemptsOrFlashcards(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("delete-preserves-data");
        Path workbookPath = TrainingSheetFixtures.writeWorkbook(tempDir, "delete-preserves-data.xlsx", List.of(
                sheet("A", List.of(row("P1", "Two Sum", platform, "")))));
        TrainingSheetImportSummary summary = importService.importWorkbook(workbookPath);
        long batchId = summary.importBatchId();

        Problem problem = problemRepository.findByPlatformAndExternalCode(platform, "P1").orElseThrow();
        progressService.updateProgress(problem.getId(), ProblemState.IN_PROGRESS, null);
        attemptService.recordAttempt(problem.getId(), SubmissionResult.WA, 60, 60, 60, 60, "first try");
        var flashcardResult = problemFlashcardService.createCard(problemFlashcardService.resolveLessonsDeckId(),
                problem.getId(), com.codefit.model.ReflectionCardSource.LESSON_LEARNED, "Prompt", "Answer", false);

        importService.deleteImportBatch(batchId);

        assertTrue(problemRepository.findById(problem.getId()).isPresent(), "the problem catalog entry must survive");
        assertEquals(ProblemState.IN_PROGRESS, progressRepository.findByProblemId(problem.getId()).orElseThrow().getState());
        assertEquals(1, attemptService.getAttempts(problem.getId()).size());
        assertTrue(new FlashcardService().getCardById(flashcardResult.card().getId()).isPresent(),
                "a flashcard created from this problem must survive its roadmap import being deleted");
        assertTrue(roadmapEntryRepository.findByProblemId(problem.getId()).isEmpty(),
                "the roadmap membership itself is what gets removed");
    }

    @Test
    void listImportBatchesReturnsBatchesNewestFirst(@TempDir Path tempDir) throws Exception {
        String platform = uniquePlatform("list-batches");
        Path first = TrainingSheetFixtures.writeWorkbook(tempDir, "first.xlsx", List.of(sheet("A", List.of(row("P1", "One", platform, "")))));
        Path second = TrainingSheetFixtures.writeWorkbook(tempDir, "second.xlsx", List.of(sheet("A", List.of(row("P2", "Two", platform, "")))));

        TrainingSheetImportSummary firstSummary = importService.importWorkbook(first,
                new ImportSourceMetadata("First Source", null, null, null));
        TrainingSheetImportSummary secondSummary = importService.importWorkbook(second,
                new ImportSourceMetadata("Second Source", null, null, null));

        List<ImportBatch> batches = importService.listImportBatches();
        assertTrue(batches.stream().anyMatch(batch -> batch.getId() == firstSummary.importBatchId()));
        assertTrue(batches.stream().anyMatch(batch -> batch.getId() == secondSummary.importBatchId()));
    }
}
