package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ImportBatch;
import com.codefit.model.ProblemAttempt;
import com.codefit.repository.ImportBatchRepository;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Imports a local Junior Training Sheet-style workbook into the problem-solving domain model
 * (#142), entirely locally: the workbook file is read from the local filesystem and never leaves
 * the machine, and nothing here touches {@code flashcards}/{@code decks} (#143).
 *
 * <p>Analysis and import are two separate concerns (#160). {@link #analyze(Path)} reads the file and
 * runs it through {@link TrainingSheetAnalyzer} — pure, in-memory, no database connection opened at
 * all — producing an {@link AnalyzedTrainingWorkbook}. {@link #previewOf(AnalyzedTrainingWorkbook)}
 * renders that analyzed model read-only; {@link #importAnalyzed} is the only method that opens a
 * database transaction, and it consumes the exact analyzed object passed to it rather than re-reading
 * the source file — so a preview can never depend on writing-then-rolling-back to compute what it
 * shows, and a confirmed import can never drift from what was reviewed.
 *
 * <p>An import is one JDBC transaction: every analyzed problem and membership is applied against a
 * single shared {@link Connection}, which is committed only if every row processes without a database
 * error and rolled back completely otherwise — a failure partway through can never leave a partial
 * import behind.
 *
 * <p>Deduplication and idempotency rely entirely on the invariants {@link ProblemService} and
 * {@link ProblemProgressService} already enforce: a repeated {@code (platform, externalCode)} is
 * matched to the same {@link com.codefit.model.Problem} rather than duplicated (so the three repeated
 * problem codes the workbook uses across roadmap stages become one problem each with multiple
 * memberships), and an existing, already-started {@link com.codefit.model.ProblemProgress} record is
 * never downgraded or blanked out by a re-import (see {@link ProblemProgressService#applyImportedState}).
 */
public class TrainingSheetImportService {

    private final ProblemService problemService;
    private final ProblemProgressService progressService;
    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ProblemAttemptRepository problemAttemptRepository;

    public TrainingSheetImportService() {
        this(new ProblemRepository(), new RoadmapEntryRepository(), new ProblemProgressRepository(), new ImportBatchRepository());
    }

    public TrainingSheetImportService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                      ProblemProgressRepository progressRepository) {
        this(problemRepository, roadmapEntryRepository, progressRepository, new ImportBatchRepository());
    }

    public TrainingSheetImportService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                      ProblemProgressRepository progressRepository, ImportBatchRepository importBatchRepository) {
        this.problemService = new ProblemService(problemRepository, roadmapEntryRepository);
        this.progressService = new ProblemProgressService(progressRepository);
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.importBatchRepository = importBatchRepository;
        this.problemAttemptRepository = new ProblemAttemptRepository();
    }

    /** Checks the workbook's structure without writing anything to the database. */
    public WorkbookValidationResult validate(Path workbookPath) {
        return TrainingSheetAnalyzer.validateStructure(TrainingSheetWorkbookReader.read(workbookPath));
    }

    /**
     * Reads and analyzes the workbook (#160): parsing, normalization, deduplication, validation, and
     * diagnostics all happen here, entirely in memory. No database connection is opened. The caller
     * (the Settings import flow) is expected to hold onto the returned object across the review dialog
     * and pass the exact same instance to {@link #importAnalyzed} if the learner confirms — never
     * re-read the file to import.
     */
    public AnalyzedTrainingWorkbook analyze(Path workbookPath) {
        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);
        return TrainingSheetAnalyzer.analyze(workbookPath.getFileName().toString(), workbook, fingerprint(workbookPath));
    }

    /** Analyzes the workbook and renders it as a {@link TrainingSheetImportSummary} preview — no
     *  database access at any point, so this is safe to call repeatedly while reviewing a file. */
    public TrainingSheetImportSummary preview(Path workbookPath) {
        return previewOf(analyze(workbookPath));
    }

    /**
     * Renders an already-analyzed workbook as the same {@link TrainingSheetImportSummary} shape a real
     * import produces (#160) — purely from {@code analyzed}, no database access at all. The review
     * dialog calls this once right after {@link #analyze}, then, if the learner confirms, passes that
     * exact {@code analyzed} instance to {@link #importAnalyzed} — the two calls describe the identical
     * data by construction, since there is no second parse in between.
     */
    public TrainingSheetImportSummary previewOf(AnalyzedTrainingWorkbook analyzed) {
        WorkbookPreviewDetails details = analyzed.details();
        return new TrainingSheetImportSummary(true, 0, 0, 0, 0, 0, details.duplicateRowsSkipped(), details.invalidRows(),
                0, 0, describeAll(analyzed.diagnostics()), null, details, analyzed.diagnostics());
    }

    /** Analyzes then imports the workbook for real, with unspecified source attribution. */
    public TrainingSheetImportSummary importWorkbook(Path workbookPath) {
        return importAnalyzed(analyze(workbookPath), ImportSourceMetadata.unspecified());
    }

    /**
     * Analyzes then imports the workbook for real, recording {@code sourceMetadata} on the resulting
     * {@link ImportBatch} (#149) so the roadmap it creates stays traceable to where it came from.
     * Equivalent to {@code importAnalyzed(analyze(workbookPath), sourceMetadata)} — kept for callers
     * (and existing tests) that don't need to inspect the analyzed model before importing.
     */
    public TrainingSheetImportSummary importWorkbook(Path workbookPath, ImportSourceMetadata sourceMetadata) {
        return importAnalyzed(analyze(workbookPath), sourceMetadata);
    }

    /**
     * Applies an already-analyzed workbook transactionally (#160) — the confirmed import consumes the
     * exact {@link AnalyzedTrainingWorkbook} the learner reviewed; the source file is never re-read or
     * re-parsed. Refuses outright (no connection opened) if the analysis found nothing importable at
     * all ({@link AnalyzedTrainingWorkbook#hasBlockingDiagnostics()}).
     *
     * <p>A roadmap-slot conflict against a <em>different</em>, already-imported workbook can only be
     * detected here (analysis only sees conflicts within the workbook being analyzed) — such a
     * membership is skipped and reported the same way an analysis-time conflict is, rather than
     * failing the whole import.
     */
    public TrainingSheetImportSummary importAnalyzed(AnalyzedTrainingWorkbook analyzed, ImportSourceMetadata sourceMetadata) {
        if (analyzed.hasBlockingDiagnostics()) {
            String reasons = analyzed.diagnostics().stream()
                    .filter(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.BLOCKING)
                    .map(TrainingSheetDiagnostic::describe)
                    .collect(Collectors.joining("; "));
            throw new WorkbookImportException("Workbook has nothing importable and was rejected: " + reasons);
        }

        try (Connection connection = DatabaseConfig.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String sourceName = sourceMetadata.sourceName() == null || sourceMetadata.sourceName().isBlank()
                        ? analyzed.workbookName()
                        : sourceMetadata.sourceName().strip();
                ImportBatch batch = importBatchRepository.save(connection,
                        new ImportBatch(0, sourceName, sourceMetadata.sourceUrl(), sourceMetadata.author(), sourceMetadata.version(), null));
                long importBatchId = batch.getId();

                ImportOutcome outcome = new ImportOutcome();
                outcome.importBatchId = importBatchId;
                Map<String, Long> problemIdsByKey = new HashMap<>();
                for (AnalyzedProblem problem : analyzed.problems()) {
                    problemIdsByKey.put(problem.key(), applyProblem(connection, problem, outcome));
                }
                for (AnalyzedRoadmapMembership membership : analyzed.memberships()) {
                    applyMembership(connection, membership, problemIdsByKey.get(membership.problemKey()), importBatchId, outcome);
                }

                connection.commit();
                return outcome.toSummary(analyzed);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw new WorkbookImportException("Import failed and was fully rolled back: " + exception.getMessage(), exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new WorkbookImportException("Unable to open database connection for import: " + exception.getMessage(), exception);
        }
    }

    private long applyProblem(Connection connection, AnalyzedProblem problem, ImportOutcome outcome) throws SQLException {
        ProblemService.ProblemUpsertResult result = problemService.upsertProblem(connection, problem.platform(), problem.externalCode(),
                problem.title(), problem.url(), problem.topic(), problem.qualityRating(), problem.learningResources());
        if (result.created()) {
            outcome.problemsCreated++;
        } else {
            outcome.problemsReused++;
            if (result.fieldsUpdated()) {
                outcome.problemsUpdated++;
            }
        }
        long problemId = result.problem().getId();

        if (problem.importedState() != null && progressService.applyImportedState(connection, problemId, problem.importedState())) {
            outcome.progressRecordsImported++;
        }
        if (problem.submissionResult() != null && problemAttemptRepository.countByProblemId(connection, problemId) == 0) {
            problemAttemptRepository.save(connection, new ProblemAttempt(0, problemId,
                    problem.submitCount() != null && problem.submitCount() > 0 ? problem.submitCount() : 1, problem.submissionResult(),
                    problem.readingSeconds(), problem.thinkingSeconds(), problem.codingSeconds(), problem.debuggingSeconds(),
                    LocalDateTime.now(), problem.attemptNotes()));
            outcome.attemptsImported++;
        }
        if (problem.perceivedDifficulty() != null || problem.solvedWith() != null || problem.actualTopic() != null || problem.approachNotes() != null) {
            boolean applied = progressService.applyImportedReflection(connection, problemId, problem.perceivedDifficulty(),
                    problem.solvedWith(), problem.actualTopic(), problem.approachNotes());
            if (applied) {
                outcome.reflectionFieldsImported++;
            }
        }
        return problemId;
    }

    private void applyMembership(Connection connection, AnalyzedRoadmapMembership membership, Long problemId, long importBatchId,
                                 ImportOutcome outcome) throws SQLException {
        try {
            ProblemService.RoadmapMembershipResult result = problemService.upsertRoadmapMembership(connection, problemId,
                    membership.stage(), membership.sequenceOrder(), membership.setNumber(), membership.mandatory(), membership.suggestedLevel());
            if (result.created()) {
                outcome.roadmapMembershipsCreated++;
            }
            roadmapEntryRepository.updateImportBatchId(connection, result.entry().getId(), importBatchId);
        } catch (IllegalStateException conflict) {
            outcome.importTimeInvalidRows++;
            outcome.importTimeDiagnostics.add(new TrainingSheetDiagnostic(membership.stage().name(), null, "Order",
                    conflict.getMessage(), TrainingSheetDiagnosticSeverity.WARNING));
        }
    }

    /** Deletes one import batch's roadmap memberships, then the batch row itself — never anything
     *  else. See {@link com.codefit.repository.RoadmapEntryRepository#deleteByImportBatchId} for why
     *  this can never touch a learner's progress, attempts, or flashcards. */
    public int deleteImportBatch(long importBatchId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int deletedMemberships = roadmapEntryRepository.deleteByImportBatchId(connection, importBatchId);
                importBatchRepository.delete(connection, importBatchId);
                connection.commit();
                return deletedMemberships;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw new WorkbookImportException("Unable to delete import batch: " + exception.getMessage(), exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new WorkbookImportException("Unable to open database connection: " + exception.getMessage(), exception);
        }
    }

    public List<ImportBatch> listImportBatches() {
        return importBatchRepository.findAll();
    }

    /** An informational snapshot of the file's identity (name/size/last-modified) at analysis time —
     *  for traceability only. Nothing in the import path depends on recomputing or matching this. */
    private String fingerprint(Path workbookPath) {
        try {
            long size = Files.size(workbookPath);
            Object lastModified = Files.getLastModifiedTime(workbookPath);
            return workbookPath.getFileName() + ":" + size + ":" + lastModified;
        } catch (IOException exception) {
            return workbookPath.getFileName() + ":unknown";
        }
    }

    private static List<String> describeAll(List<TrainingSheetDiagnostic> diagnostics) {
        return diagnostics.stream().map(TrainingSheetDiagnostic::describe).toList();
    }

    /** Accumulates the database-dependent results of applying an {@link AnalyzedTrainingWorkbook}:
     *  created/updated/reused counts, plus any roadmap-slot conflict only discoverable once a real
     *  connection is available (a conflict against a different, already-imported workbook). */
    private static final class ImportOutcome {
        long importBatchId;
        int problemsCreated;
        int problemsUpdated;
        int problemsReused;
        int roadmapMembershipsCreated;
        int progressRecordsImported;
        int attemptsImported;
        int reflectionFieldsImported;
        int importTimeInvalidRows;
        final List<TrainingSheetDiagnostic> importTimeDiagnostics = new ArrayList<>();

        TrainingSheetImportSummary toSummary(AnalyzedTrainingWorkbook analyzed) {
            List<TrainingSheetDiagnostic> allDiagnostics = new ArrayList<>(analyzed.diagnostics());
            allDiagnostics.addAll(importTimeDiagnostics);
            return new TrainingSheetImportSummary(false, problemsCreated, problemsUpdated, problemsReused, roadmapMembershipsCreated,
                    progressRecordsImported, analyzed.details().duplicateRowsSkipped(), analyzed.details().invalidRows() + importTimeInvalidRows,
                    attemptsImported, reflectionFieldsImported, describeAll(allDiagnostics), importBatchId, analyzed.details(),
                    List.copyOf(allDiagnostics));
        }
    }
}
