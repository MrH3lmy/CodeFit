package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.ImportBatch;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ImportBatchRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Imports a local Junior Training Sheet-style workbook into the problem-solving domain model
 * (#142), entirely locally: the workbook file is read from the local filesystem and never leaves
 * the machine, and nothing here touches {@code flashcards}/{@code decks} (#143).
 *
 * <p>An import is one JDBC transaction: every sheet's rows are processed against a single shared
 * {@link Connection}, which is committed only if every row processes without a database error and
 * rolled back completely otherwise — a failure partway through can never leave a partial import
 * behind. {@link #preview(Path)} runs the exact same logic but always rolls back at the end, so it
 * can report what a real import would do without writing anything.
 *
 * <p>Deduplication and idempotency rely entirely on the invariants {@link ProblemService} and
 * {@link ProblemProgressService} already enforce: a repeated {@code (platform, externalCode)} is
 * matched to the same {@link Problem} rather than duplicated (so the three repeated problem codes
 * the workbook uses across roadmap stages become one problem each with multiple memberships), and an
 * existing, already-started {@link com.codefit.model.ProblemProgress} record is never downgraded or
 * blanked out by a re-import (see {@link ProblemProgressService#applyImportedState}).
 */
public class TrainingSheetImportService {

    private static final String TOPICS_SHEET_NAME = "Topics";
    private static final String DEFAULT_PLATFORM = "Training Sheet";

    private static final Map<String, DifficultyLevel> LEVEL_ALIASES = Map.of(
            "easy", DifficultyLevel.EASY,
            "medium", DifficultyLevel.MEDIUM,
            "med", DifficultyLevel.MEDIUM,
            "hard", DifficultyLevel.HARD);

    private static final Map<String, ProblemState> SOLVED_STATUS_ALIASES = Map.of(
            "ac", ProblemState.SOLVED,
            "acx", ProblemState.SOLVED,
            "accepted", ProblemState.SOLVED,
            "solved", ProblemState.SOLVED,
            "done", ProblemState.SOLVED);

    private static final Map<String, ProblemState> IN_PROGRESS_STATUS_ALIASES = Map.of(
            "in progress", ProblemState.IN_PROGRESS,
            "in-progress", ProblemState.IN_PROGRESS,
            "started", ProblemState.IN_PROGRESS,
            "wip", ProblemState.IN_PROGRESS);

    private static final Map<String, ProblemState> REVISIT_STATUS_ALIASES = Map.of(
            "revisit", ProblemState.NEEDS_REVISIT,
            "review", ProblemState.NEEDS_REVISIT,
            "redo", ProblemState.NEEDS_REVISIT);

    /** Recognized but deliberately not applied: an unsolved verdict must not overwrite existing progress or count as an unrecognized-status warning. */
    private static final Set<String> RECOGNIZED_NON_ADVANCING_STATUSES = Set.of(
            "wa", "tle", "rte", "mle", "cs", "not started", "todo", "-");

    private final ProblemRepository problemRepository;
    private final ProblemService problemService;
    private final ProblemProgressService progressService;
    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ImportBatchRepository importBatchRepository;

    public TrainingSheetImportService() {
        this(new ProblemRepository(), new RoadmapEntryRepository(), new ProblemProgressRepository(), new ImportBatchRepository());
    }

    public TrainingSheetImportService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                      ProblemProgressRepository progressRepository) {
        this(problemRepository, roadmapEntryRepository, progressRepository, new ImportBatchRepository());
    }

    public TrainingSheetImportService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                      ProblemProgressRepository progressRepository, ImportBatchRepository importBatchRepository) {
        this.problemRepository = problemRepository;
        this.problemService = new ProblemService(problemRepository, roadmapEntryRepository);
        this.progressService = new ProblemProgressService(progressRepository);
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.importBatchRepository = importBatchRepository;
    }

    /** Checks the workbook's structure without writing anything to the database. */
    public WorkbookValidationResult validate(Path workbookPath) {
        return validateStructure(TrainingSheetWorkbookReader.read(workbookPath));
    }

    /** Runs the full import logic but rolls back at the end, reporting what a real import would do. */
    public TrainingSheetImportSummary preview(Path workbookPath) {
        return runImport(workbookPath, true, ImportSourceMetadata.unspecified());
    }

    /** Imports the workbook for real, inside one all-or-nothing transaction, with unspecified source attribution. */
    public TrainingSheetImportSummary importWorkbook(Path workbookPath) {
        return runImport(workbookPath, false, ImportSourceMetadata.unspecified());
    }

    /**
     * Imports the workbook for real, recording {@code sourceMetadata} on the resulting
     * {@link ImportBatch} (#149) so the roadmap it creates stays traceable to where it came from.
     * {@code sourceMetadata.sourceName()} falls back to the workbook's file name when blank.
     */
    public TrainingSheetImportSummary importWorkbook(Path workbookPath, ImportSourceMetadata sourceMetadata) {
        return runImport(workbookPath, false, sourceMetadata);
    }

    private TrainingSheetImportSummary runImport(Path workbookPath, boolean dryRun, ImportSourceMetadata sourceMetadata) {
        ParsedWorkbook workbook = TrainingSheetWorkbookReader.read(workbookPath);
        WorkbookValidationResult validation = validateStructure(workbook);
        if (!validation.valid()) {
            throw new WorkbookImportException("Workbook failed validation: " + String.join("; ", validation.structuralWarnings()));
        }

        try (Connection connection = DatabaseConfig.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ImportContext context = new ImportContext();
                context.warnings.addAll(validation.structuralWarnings());

                String sourceName = sourceMetadata.sourceName() == null || sourceMetadata.sourceName().isBlank()
                        ? workbookPath.getFileName().toString()
                        : sourceMetadata.sourceName().strip();
                ImportBatch batch = importBatchRepository.save(connection,
                        new ImportBatch(0, sourceName, sourceMetadata.sourceUrl(), sourceMetadata.author(), sourceMetadata.version(), null));
                context.importBatchId = batch.getId();

                for (RoadmapStage stage : RoadmapStage.values()) {
                    Optional<ParsedSheet> sheet = workbook.sheet(stage.name());
                    if (sheet.isPresent()) {
                        importRoadmapSheet(connection, stage, sheet.get(), context);
                    }
                }
                Optional<ParsedSheet> topicsSheet = workbook.sheet(TOPICS_SHEET_NAME);
                if (topicsSheet.isPresent()) {
                    importTopicsSheet(connection, topicsSheet.get(), context);
                }

                if (dryRun) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
                return context.toSummary(dryRun);
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

    private void importRoadmapSheet(Connection connection, RoadmapStage stage, ParsedSheet sheet, ImportContext context) throws SQLException {
        Set<String> seenKeysInSheet = new HashSet<>();
        int sequenceCounter = 0;
        for (ParsedWorkbookRow row : sheet.rows()) {
            sequenceCounter++;
            String code = row.get(TrainingSheetColumns.CODE);
            String title = row.get(TrainingSheetColumns.TITLE);
            if (code == null || title == null) {
                context.invalidRows++;
                context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber() + ": missing problem code or title, skipped.");
                continue;
            }

            String platform = Optional.ofNullable(row.get(TrainingSheetColumns.PLATFORM)).orElse(DEFAULT_PLATFORM);
            String dedupeKey = platform.toLowerCase(Locale.ROOT) + "::" + code.toLowerCase(Locale.ROOT);
            if (!seenKeysInSheet.add(dedupeKey)) {
                context.duplicateRowsSkipped++;
                context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber()
                        + ": duplicate problem code '" + code + "' within this sheet, skipped.");
                continue;
            }

            String url = row.get(TrainingSheetColumns.URL);
            String topic = row.get(TrainingSheetColumns.TOPIC);
            Integer quality = parseQuality(row, stage, context);
            String resource = row.get(TrainingSheetColumns.RESOURCES);
            List<String> resources = resource == null ? List.of() : List.of(resource);

            ProblemService.ProblemUpsertResult problemResult =
                    problemService.upsertProblem(connection, platform, code, title, url, topic, quality, resources);
            if (problemResult.created()) {
                context.problemsCreated++;
            } else {
                context.problemsReused++;
                if (problemResult.fieldsUpdated()) {
                    context.problemsUpdated++;
                }
            }

            int sequenceOrder = parseOrder(row, sequenceCounter);
            Integer setNumber = parseSetNumber(row, stage, context);
            boolean mandatory = parseMandatory(row);
            DifficultyLevel suggestedLevel = parseDifficultyLevel(row.get(TrainingSheetColumns.LEVEL), stage, context);

            ProblemService.RoadmapMembershipResult membershipResult;
            try {
                membershipResult = problemService.upsertRoadmapMembership(
                        connection, problemResult.problem().getId(), stage, sequenceOrder, setNumber, mandatory, suggestedLevel);
            } catch (IllegalStateException conflict) {
                context.invalidRows++;
                context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber() + ": " + conflict.getMessage());
                continue;
            }
            if (membershipResult.created()) {
                context.roadmapMembershipsCreated++;
            }
            roadmapEntryRepository.updateImportBatchId(connection, membershipResult.entry().getId(), context.importBatchId);

            String rawStatus = row.get(TrainingSheetColumns.STATUS);
            ProblemState importedState = parseImportedState(rawStatus);
            if (importedState != null) {
                boolean applied = progressService.applyImportedState(connection, problemResult.problem().getId(), importedState);
                if (applied) {
                    context.progressRecordsImported++;
                }
            } else if (rawStatus != null && !isRecognizedStatusToken(rawStatus)) {
                context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber()
                        + ": unrecognized status '" + rawStatus + "', progress not imported for this row.");
            }
        }
    }

    /**
     * The {@code Topics} sheet is alternative classification metadata over problems the roadmap
     * sheets already imported, not a source of new problems: a code with no matching problem is a
     * warning, never an insert.
     */
    private void importTopicsSheet(Connection connection, ParsedSheet sheet, ImportContext context) throws SQLException {
        for (ParsedWorkbookRow row : sheet.rows()) {
            String code = row.get(TrainingSheetColumns.CODE);
            String topic = row.get(TrainingSheetColumns.TOPIC);
            if (code == null || topic == null) {
                context.invalidRows++;
                context.warnings.add("Sheet " + TOPICS_SHEET_NAME + ", row " + row.rowNumber() + ": missing problem code or topic, skipped.");
                continue;
            }

            String platform = row.get(TrainingSheetColumns.PLATFORM);
            List<Problem> matches = platform != null
                    ? problemRepository.findByPlatformAndExternalCode(connection, platform, code).map(List::of).orElse(List.of())
                    : problemRepository.findAllByExternalCode(connection, code);
            if (matches.isEmpty()) {
                context.warnings.add("Sheet " + TOPICS_SHEET_NAME + ", row " + row.rowNumber()
                        + ": no imported problem found for code '" + code + "', topic not applied.");
                continue;
            }
            for (Problem problem : matches) {
                if (!topic.equalsIgnoreCase(problem.getTopic())) {
                    problem.setTopic(topic);
                    problemRepository.update(connection, problem);
                    context.problemsUpdated++;
                }
            }
        }
    }

    private WorkbookValidationResult validateStructure(ParsedWorkbook workbook) {
        List<String> missingRoadmapSheets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int usableRoadmapSheets = 0;

        for (RoadmapStage stage : RoadmapStage.values()) {
            Optional<ParsedSheet> sheet = workbook.sheet(stage.name());
            if (sheet.isEmpty()) {
                missingRoadmapSheets.add(stage.name());
                continue;
            }
            ParsedSheet parsedSheet = sheet.get();
            if (!parsedSheet.hasColumn(TrainingSheetColumns.CODE) || !parsedSheet.hasColumn(TrainingSheetColumns.TITLE)) {
                warnings.add("Sheet " + stage.name() + " is present but its header row has no recognizable "
                        + "problem code/title columns; it will be skipped.");
                continue;
            }
            usableRoadmapSheets++;
        }
        if (!missingRoadmapSheets.isEmpty()) {
            warnings.add("Workbook has no sheet for roadmap stage(s): " + String.join(", ", missingRoadmapSheets) + ".");
        }

        boolean valid = usableRoadmapSheets > 0;
        if (!valid) {
            warnings.add(0, "No usable roadmap sheet found (expected sheets named A, B, C1, C2, D1, D2, D3 "
                    + "with recognizable code/title columns).");
        }
        return new WorkbookValidationResult(valid, missingRoadmapSheets, warnings);
    }

    private Integer parseQuality(ParsedWorkbookRow row, RoadmapStage stage, ImportContext context) {
        String raw = row.get(TrainingSheetColumns.QUALITY);
        if (raw == null) {
            return null;
        }
        try {
            int value = (int) Math.round(Double.parseDouble(raw));
            if (value < 1 || value > 5) {
                context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber()
                        + ": quality '" + raw + "' is outside 1-5, ignored.");
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber()
                    + ": unrecognized quality value '" + raw + "', ignored.");
            return null;
        }
    }

    private Integer parseSetNumber(ParsedWorkbookRow row, RoadmapStage stage, ImportContext context) {
        String raw = row.get(TrainingSheetColumns.SET_NUMBER);
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            context.warnings.add("Sheet " + stage.name() + ", row " + row.rowNumber()
                    + ": unrecognized set number '" + raw + "', ignored.");
            return null;
        }
        return Integer.parseInt(digits);
    }

    private int parseOrder(ParsedWorkbookRow row, int fallbackSequence) {
        String raw = row.get(TrainingSheetColumns.ORDER);
        if (raw == null) {
            return fallbackSequence;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            return fallbackSequence;
        }
    }

    private static final Set<String> MANDATORY_FALSE_VALUES = Set.of("no", "n", "false", "0", "optional");

    private boolean parseMandatory(ParsedWorkbookRow row) {
        String raw = row.get(TrainingSheetColumns.MANDATORY);
        if (raw == null) {
            return true;
        }
        return !MANDATORY_FALSE_VALUES.contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    private DifficultyLevel parseDifficultyLevel(String raw, RoadmapStage stage, ImportContext context) {
        if (raw == null) {
            return null;
        }
        DifficultyLevel level = LEVEL_ALIASES.get(raw.strip().toLowerCase(Locale.ROOT));
        if (level == null) {
            context.warnings.add("Sheet " + stage.name() + ": unrecognized level '" + raw + "', ignored.");
        }
        return level;
    }

    private ProblemState parseImportedState(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if (SOLVED_STATUS_ALIASES.containsKey(normalized)) {
            return SOLVED_STATUS_ALIASES.get(normalized);
        }
        if (IN_PROGRESS_STATUS_ALIASES.containsKey(normalized)) {
            return IN_PROGRESS_STATUS_ALIASES.get(normalized);
        }
        return REVISIT_STATUS_ALIASES.get(normalized);
    }

    private boolean isRecognizedStatusToken(String raw) {
        return RECOGNIZED_NON_ADVANCING_STATUSES.contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    private static final class ImportContext {
        int problemsCreated;
        int problemsUpdated;
        int problemsReused;
        int roadmapMembershipsCreated;
        int progressRecordsImported;
        int duplicateRowsSkipped;
        int invalidRows;
        long importBatchId;
        final List<String> warnings = new ArrayList<>();

        TrainingSheetImportSummary toSummary(boolean dryRun) {
            // A dry run's batch row is rolled back along with everything else, so it never durably
            // exists - reporting its id would let a caller try to reference/delete a batch that isn't there.
            Long reportedBatchId = dryRun ? null : importBatchId;
            return new TrainingSheetImportSummary(dryRun, problemsCreated, problemsUpdated, problemsReused,
                    roadmapMembershipsCreated, progressRecordsImported, duplicateRowsSkipped, invalidRows,
                    List.copyOf(warnings), reportedBatchId);
        }
    }
}
