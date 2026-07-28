package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a {@link ParsedWorkbook} into an {@link AnalyzedTrainingWorkbook} (#160): every row is
 * normalized, validated, deduplicated, and turned into an {@link AnalyzedProblem}/
 * {@link AnalyzedRoadmapMembership}, entirely in memory. This class never opens a database
 * connection, never starts a transaction, and never creates an import batch — "analyze" and "apply to
 * the database" are two completely separate concerns, so a preview can never accidentally depend on
 * writing-then-rolling-back to compute what it shows.
 *
 * <p>{@link TrainingSheetImportService} is the only caller: it reads the workbook file and passes the
 * resulting {@link ParsedWorkbook} in, then either renders the result (a preview) or applies it inside
 * a real transaction (a confirmed import) via {@link TrainingSheetImportService#importAnalyzed}.
 */
final class TrainingSheetAnalyzer {

    static final String TOPICS_SHEET_NAME = "Topics";
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

    private static final Set<String> MANDATORY_FALSE_VALUES = Set.of("no", "n", "false", "0", "optional");

    private TrainingSheetAnalyzer() {
    }

    static AnalyzedTrainingWorkbook analyze(String workbookName, ParsedWorkbook workbook, String fileFingerprint) {
        WorkbookValidationResult validation = validateStructure(workbook);
        WorkbookProfile profile = new WorkbookProfile(
                validation.valid() ? "Junior Training Sheet" : "Generic training workbook",
                workbook.detectedVersion() != null ? workbook.detectedVersion() : "Not detected");
        AnalysisContext context = new AnalysisContext(validation, profile);
        if (validation.valid()) {
            for (RoadmapStage stage : RoadmapStage.values()) {
                workbook.sheet(stage.name()).ifPresent(sheet -> analyzeRoadmapSheet(stage, sheet, context));
            }
            workbook.sheet(TOPICS_SHEET_NAME).ifPresent(sheet -> analyzeTopicsSheet(sheet, context));
        }
        return context.toAnalyzedWorkbook(workbookName, fileFingerprint);
    }

    static WorkbookValidationResult validateStructure(ParsedWorkbook workbook) {
        List<String> missingRoadmapSheets = new ArrayList<>();
        List<String> recognizedSheets = new ArrayList<>();
        List<String> ignoredSheets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<TrainingSheetDiagnostic> diagnostics = new ArrayList<>();
        int usableRoadmapSheets = 0;
        Set<String> consumedSheetNames = new HashSet<>();

        for (RoadmapStage stage : RoadmapStage.values()) {
            Optional<ParsedSheet> sheet = workbook.sheet(stage.name());
            if (sheet.isEmpty()) {
                missingRoadmapSheets.add(stage.name());
                continue;
            }
            consumedSheetNames.add(stage.name());
            ParsedSheet parsedSheet = sheet.get();
            if (!parsedSheet.hasColumn(TrainingSheetColumns.CODE) || !parsedSheet.hasColumn(TrainingSheetColumns.TITLE)) {
                String reason = "header row has no recognizable problem code/title columns; it will be skipped.";
                warnings.add("Sheet " + stage.name() + " is present but its " + reason);
                diagnostics.add(new TrainingSheetDiagnostic(stage.name(), null, null, reason, TrainingSheetDiagnosticSeverity.WARNING));
                ignoredSheets.add(stage.name());
                continue;
            }
            usableRoadmapSheets++;
            recognizedSheets.add(stage.name());
        }
        if (workbook.sheet(TOPICS_SHEET_NAME).isPresent()) {
            consumedSheetNames.add(TOPICS_SHEET_NAME);
            recognizedSheets.add(TOPICS_SHEET_NAME);
        }
        for (String sheetName : workbook.sheetsByName().keySet()) {
            if (!consumedSheetNames.contains(sheetName)) {
                ignoredSheets.add(sheetName);
            }
        }

        if (!missingRoadmapSheets.isEmpty()) {
            String reason = "Workbook has no sheet for roadmap stage(s): " + String.join(", ", missingRoadmapSheets) + ".";
            warnings.add(reason);
            diagnostics.add(new TrainingSheetDiagnostic(null, null, null, reason, TrainingSheetDiagnosticSeverity.WARNING));
        }

        boolean valid = usableRoadmapSheets > 0;
        if (!valid) {
            String reason = "No usable roadmap sheet found (expected sheets named A, B, C1, C2, D1, D2, D3 "
                    + "with recognizable code/title columns).";
            warnings.add(0, reason);
            diagnostics.add(0, new TrainingSheetDiagnostic(null, null, null, reason, TrainingSheetDiagnosticSeverity.BLOCKING));
        }
        return new WorkbookValidationResult(valid, missingRoadmapSheets, warnings, recognizedSheets, ignoredSheets, diagnostics);
    }

    private static void analyzeRoadmapSheet(RoadmapStage stage, ParsedSheet sheet, AnalysisContext context) {
        context.mergeSkippedReasons(sheet.droppedRowReasons());
        context.detectedRowCountByStage.put(stage, sheet.detectedRowCount());
        Set<String> seenKeysInSheet = new HashSet<>();
        Map<Integer, String> slotOwnersInStage = context.slotOwnersByStage.computeIfAbsent(stage, ignored -> new HashMap<>());
        int sequenceCounter = 0;
        for (ParsedWorkbookRow row : sheet.rows()) {
            sequenceCounter++;
            String code = row.get(TrainingSheetColumns.CODE);
            String title = row.get(TrainingSheetColumns.TITLE);
            if (code == null || title == null) {
                context.invalidRows++;
                context.rowsSkippedByReason.merge("missing problem code or title", 1, Integer::sum);
                context.warn(stage.name(), row.rowNumber(), null, "missing problem code or title, skipped.");
                continue;
            }

            String url = firstNonBlank(row.get(TrainingSheetColumns.URL),
                    row.get(TrainingSheetWorkbookReader.CODE_URL), row.get(TrainingSheetWorkbookReader.TITLE_URL));
            // Resolving the platform has no counting side effects (#160): a row can still turn out to
            // be a duplicate or lose a roadmap-slot conflict below, and neither should inflate the
            // explicit/inferred/unknown platform-source counts - those only count rows actually
            // accepted into a membership, right alongside platformCounts itself.
            PlatformResolution platformResolution = resolvePlatform(row, code, url);
            String platform = platformResolution.platform();

            String dedupeKey = platform.toLowerCase(Locale.ROOT) + "::" + code.toLowerCase(Locale.ROOT);
            if (!seenKeysInSheet.add(dedupeKey)) {
                context.duplicateRowsSkipped++;
                context.rowsSkippedByReason.merge("duplicate problem code within sheet", 1, Integer::sum);
                context.warn(stage.name(), row.rowNumber(), "Code",
                        "duplicate problem code '" + code + "' within this sheet, skipped.");
                continue;
            }

            // The problem catalog entry is updated from this row's own values regardless of whether
            // this row goes on to win its roadmap slot below - matching ProblemService#upsertProblem's
            // existing behavior, where the catalog upsert always runs before a later membership
            // conflict is even checked, so a row that loses its slot still isn't a wasted read.
            String topic = row.get(TrainingSheetColumns.TOPIC);
            Integer quality = parseQuality(row, stage, context);
            if (quality != null) {
                context.qualityMetadataCount++;
            }
            String resource = row.get(TrainingSheetColumns.RESOURCES);
            List<String> resources = resource == null ? List.of() : List.of(resource);
            ProblemAccumulator accumulator = context.problemsByKey.computeIfAbsent(dedupeKey, ignored -> new ProblemAccumulator(platform, code));
            accumulator.updateCatalog(title, url, topic, quality, resources);

            int sequenceOrder = parseOrder(row, sequenceCounter);
            String existingOwner = slotOwnersInStage.get(sequenceOrder);
            if (existingOwner != null && !existingOwner.equals(dedupeKey)) {
                context.invalidRows++;
                context.rowsSkippedByReason.merge("roadmap slot conflict", 1, Integer::sum);
                context.warn(stage.name(), row.rowNumber(), "Order", "Roadmap slot " + stage + "#" + sequenceOrder
                        + " is already claimed by another problem in this workbook.");
                continue;
            }
            slotOwnersInStage.put(sequenceOrder, dedupeKey);

            Integer setNumber = parseSetNumber(row, stage, context);
            boolean mandatory = parseMandatory(row);
            DifficultyLevel suggestedLevel = parseDifficultyLevel(row, stage, context);
            if (suggestedLevel != null) {
                context.suggestedLevelMetadataCount++;
            }

            context.memberships.add(new AnalyzedRoadmapMembership(dedupeKey, stage, sequenceOrder, setNumber, mandatory, suggestedLevel));
            context.stageMembershipCounts.merge(stage, 1, Integer::sum);
            context.platformCounts.merge(platform, 1, Integer::sum);
            switch (platformResolution.source()) {
                case EXPLICIT -> context.explicitPlatformCount++;
                case INFERRED -> context.inferredPlatformCount++;
                case UNKNOWN -> context.unknownPlatformCount++;
            }
            if (url != null) {
                context.hyperlinksFound++;
            } else {
                context.hyperlinksMissing++;
            }
            if (topic != null) {
                context.topicCounts.merge(topic, 1, Integer::sum);
            }

            String rawStatus = row.get(TrainingSheetColumns.STATUS);
            ProblemState importedState = parseImportedState(rawStatus);
            switch (importedState == null ? ProblemState.NOT_STARTED : importedState) {
                case SOLVED -> context.solvedCount++;
                case IN_PROGRESS -> context.inProgressCount++;
                case NEEDS_REVISIT -> context.revisitCount++;
                default -> context.notStartedCount++;
            }
            if (importedState != null) {
                accumulator.offerImportedState(importedState);
            } else if (rawStatus != null && !isRecognizedStatusToken(rawStatus)) {
                context.warn(stage.name(), row.rowNumber(), "Status",
                        "unrecognized status '" + rawStatus + "', progress not imported for this row.");
            }

            SubmissionResult submissionResult = parseSubmissionResult(rawStatus);
            if (submissionResult != null) {
                Integer submitCount = parsePositiveInt(row.get(TrainingSheetColumns.SUBMIT_COUNT));
                int attemptNumber = submitCount != null && submitCount > 0 ? submitCount : 1;
                Integer readingSeconds = parseMinutesToSeconds(row.get(TrainingSheetColumns.READING_TIME_MINUTES));
                Integer thinkingSeconds = parseMinutesToSeconds(row.get(TrainingSheetColumns.THINKING_TIME_MINUTES));
                Integer codingSeconds = parseMinutesToSeconds(row.get(TrainingSheetColumns.CODING_TIME_MINUTES));
                Integer debuggingSeconds = parseMinutesToSeconds(row.get(TrainingSheetColumns.DEBUG_TIME_MINUTES));
                String attemptNotes = row.get(TrainingSheetColumns.APPROACH_NOTES);
                accumulator.offerAttempt(submissionResult, attemptNumber, readingSeconds, thinkingSeconds, codingSeconds, debuggingSeconds, attemptNotes);
            }

            Integer perceivedDifficulty = parsePerceivedDifficulty(row, stage, context);
            SolvedWith solvedWith = parseSolvedWith(row.get(TrainingSheetColumns.INDEPENDENCE));
            if (solvedWith != null) {
                context.assistanceMetadataCount++;
            }
            String approachNotes = row.get(TrainingSheetColumns.APPROACH_NOTES);
            accumulator.offerReflection(perceivedDifficulty, solvedWith, topic, approachNotes);
        }
    }

    /** Pure: resolves a row's platform with no counting side effects (#160) — see the call site for
     *  why the resulting {@link PlatformResolution#source()} is only counted once the row is accepted. */
    private static PlatformResolution resolvePlatform(ParsedWorkbookRow row, String code, String url) {
        String explicitPlatform = row.get(TrainingSheetColumns.PLATFORM);
        if (explicitPlatform != null) {
            return new PlatformResolution(explicitPlatform, PlatformSource.EXPLICIT);
        }
        Optional<String> inferred = PlatformInference.infer(code, url);
        if (inferred.isPresent()) {
            return new PlatformResolution(inferred.get(), PlatformSource.INFERRED);
        }
        return new PlatformResolution(DEFAULT_PLATFORM, PlatformSource.UNKNOWN);
    }

    /**
     * The {@code Topics} sheet is alternative classification metadata over problems the roadmap
     * sheets already found in this same workbook, not a source of new problems: a code with no
     * matching problem is a warning, never an insert. Matching happens purely against
     * {@code context.problemsByKey} (this workbook's own analyzed problems) rather than a database
     * query — a Topics row referencing a problem imported by a <em>different</em> workbook is reported
     * as unmatched, since analysis never touches the database.
     */
    private static void analyzeTopicsSheet(ParsedSheet sheet, AnalysisContext context) {
        context.mergeSkippedReasons(sheet.droppedRowReasons());
        Map<String, List<String>> keysByCodeOnly = new LinkedHashMap<>();
        for (Map.Entry<String, ProblemAccumulator> entry : context.problemsByKey.entrySet()) {
            keysByCodeOnly.computeIfAbsent(entry.getValue().externalCode.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entry.getKey());
        }

        for (ParsedWorkbookRow row : sheet.rows()) {
            String code = row.get(TrainingSheetColumns.CODE);
            String topic = firstNonBlank(row.get(TrainingSheetColumns.CURATED_CATEGORY), row.get(TrainingSheetColumns.TOPIC),
                    row.get(TrainingSheetColumns.CATEGORY_CODE));
            if (code == null || topic == null) {
                context.invalidRows++;
                context.rowsSkippedByReason.merge("missing problem code or topic (Topics sheet)", 1, Integer::sum);
                context.warn(TOPICS_SHEET_NAME, row.rowNumber(), null, "missing problem code or topic, skipped.");
                continue;
            }

            String platform = row.get(TrainingSheetColumns.PLATFORM);
            List<String> matchKeys;
            if (platform != null) {
                String key = platform.toLowerCase(Locale.ROOT) + "::" + code.toLowerCase(Locale.ROOT);
                matchKeys = context.problemsByKey.containsKey(key) ? List.of(key) : List.of();
            } else {
                matchKeys = keysByCodeOnly.getOrDefault(code.toLowerCase(Locale.ROOT), List.of());
            }
            if (matchKeys.isEmpty()) {
                context.rowsSkippedByReason.merge("Topics row with no matching problem", 1, Integer::sum);
                context.warn(TOPICS_SHEET_NAME, row.rowNumber(), "Code", "no imported problem found for code '" + code + "', topic not applied.");
                continue;
            }
            context.topicCounts.merge(topic, 1, Integer::sum);
            for (String key : matchKeys) {
                context.problemsByKey.get(key).topic = topic;
            }
        }
    }

    private static Integer parseQuality(ParsedWorkbookRow row, RoadmapStage stage, AnalysisContext context) {
        String raw = row.get(TrainingSheetColumns.QUALITY);
        if (raw == null) {
            return null;
        }
        try {
            int value = (int) Math.round(Double.parseDouble(raw));
            if (value < 1 || value > 5) {
                context.warn(stage.name(), row.rowNumber(), "Quality", "quality '" + raw + "' is outside 1-5, ignored.");
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            context.warn(stage.name(), row.rowNumber(), "Quality", "unrecognized quality value '" + raw + "', ignored.");
            return null;
        }
    }

    private static Integer parseSetNumber(ParsedWorkbookRow row, RoadmapStage stage, AnalysisContext context) {
        String raw = row.get(TrainingSheetColumns.SET_NUMBER);
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            context.warn(stage.name(), row.rowNumber(), "Set", "unrecognized set number '" + raw + "', ignored.");
            return null;
        }
        return Integer.parseInt(digits);
    }

    private static int parseOrder(ParsedWorkbookRow row, int fallbackSequence) {
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

    private static boolean parseMandatory(ParsedWorkbookRow row) {
        String raw = row.get(TrainingSheetColumns.MANDATORY);
        if (raw == null) {
            return true;
        }
        return !MANDATORY_FALSE_VALUES.contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    private static DifficultyLevel parseDifficultyLevel(ParsedWorkbookRow row, RoadmapStage stage, AnalysisContext context) {
        String raw = row.get(TrainingSheetColumns.LEVEL);
        if (raw == null) {
            return null;
        }
        DifficultyLevel level = LEVEL_ALIASES.get(raw.strip().toLowerCase(Locale.ROOT));
        if (level == null) {
            context.warn(stage.name(), row.rowNumber(), "Level", "unrecognized level '" + raw + "', ignored.");
        }
        return level;
    }

    private static ProblemState parseImportedState(String raw) {
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

    private static boolean isRecognizedStatusToken(String raw) {
        return RECOGNIZED_NON_ADVANCING_STATUSES.contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /** The workbook's status token as a judge verdict, or {@code null} if blank/unrecognized (e.g. "In Progress" isn't a verdict). */
    private static SubmissionResult parseSubmissionResult(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        try {
            return SubmissionResult.valueOf(rawStatus.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAVerdict) {
            return null;
        }
    }

    /** "By yourself? Yes/No/Hint" — "No" is treated as editorial-assisted rather than a full solution
     *  copy, since the workbook doesn't distinguish the two; see {@link SolvedWith}. */
    private static SolvedWith parseSolvedWith(String rawIndependence) {
        if (rawIndependence == null) {
            return null;
        }
        return switch (rawIndependence.strip().toLowerCase(Locale.ROOT)) {
            case "yes", "y" -> SolvedWith.SELF;
            case "hint" -> SolvedWith.HINT;
            case "no", "n" -> SolvedWith.EDITORIAL;
            default -> null;
        };
    }

    private static Integer parsePerceivedDifficulty(ParsedWorkbookRow row, RoadmapStage stage, AnalysisContext context) {
        String raw = row.get(TrainingSheetColumns.PERCEIVED_DIFFICULTY);
        if (raw == null) {
            return null;
        }
        try {
            int value = (int) Math.round(Double.parseDouble(raw));
            if (value < 1 || value > 10) {
                context.warn(stage.name(), row.rowNumber(), "Perceived Difficulty", "perceived difficulty '" + raw + "' is outside 1-10, ignored.");
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            context.warn(stage.name(), row.rowNumber(), "Perceived Difficulty", "unrecognized perceived difficulty '" + raw + "', ignored.");
            return null;
        }
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseMinutesToSeconds(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double minutes = Double.parseDouble(raw);
            return (int) Math.round(minutes * 60);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Mutable accumulator for one {@code (platform, externalCode)} key while rows are processed, in
     *  workbook order, before freezing into an immutable {@link AnalyzedProblem}. */
    private static final class ProblemAccumulator {
        final String platform;
        final String externalCode;
        String title;
        String url;
        String topic;
        Integer qualityRating;
        List<String> learningResources = List.of();
        ProblemState importedState;
        SubmissionResult submissionResult;
        Integer submitCount;
        Integer readingSeconds;
        Integer thinkingSeconds;
        Integer codingSeconds;
        Integer debuggingSeconds;
        String attemptNotes;
        Integer perceivedDifficulty;
        SolvedWith solvedWith;
        String actualTopic;
        String approachNotes;

        ProblemAccumulator(String platform, String externalCode) {
            this.platform = platform;
            this.externalCode = externalCode;
        }

        /** Matches {@link ProblemService#upsertProblem}'s existing behavior: the newest row's catalog
         *  values always win, even over an earlier row's non-null value. */
        void updateCatalog(String title, String url, String topic, Integer qualityRating, List<String> learningResources) {
            this.title = title;
            this.url = url;
            this.topic = topic;
            this.qualityRating = qualityRating;
            this.learningResources = learningResources;
        }

        void offerImportedState(ProblemState state) {
            if (importedState == null) {
                importedState = state;
            }
        }

        void offerAttempt(SubmissionResult submissionResult, int attemptNumber, Integer readingSeconds, Integer thinkingSeconds,
                          Integer codingSeconds, Integer debuggingSeconds, String attemptNotes) {
            if (this.submissionResult != null) {
                return;
            }
            this.submissionResult = submissionResult;
            this.submitCount = attemptNumber;
            this.readingSeconds = readingSeconds;
            this.thinkingSeconds = thinkingSeconds;
            this.codingSeconds = codingSeconds;
            this.debuggingSeconds = debuggingSeconds;
            this.attemptNotes = attemptNotes;
        }

        void offerReflection(Integer perceivedDifficulty, SolvedWith solvedWith, String actualTopic, String approachNotes) {
            if (this.perceivedDifficulty == null && perceivedDifficulty != null) {
                this.perceivedDifficulty = perceivedDifficulty;
            }
            if (this.solvedWith == null && solvedWith != null) {
                this.solvedWith = solvedWith;
            }
            if (this.actualTopic == null && actualTopic != null) {
                this.actualTopic = actualTopic;
            }
            if (this.approachNotes == null && approachNotes != null) {
                this.approachNotes = approachNotes;
            }
        }

        AnalyzedProblem toAnalyzedProblem(String key) {
            return new AnalyzedProblem(key, platform, externalCode, title, url, topic, qualityRating, learningResources,
                    importedState, submissionResult, submitCount, readingSeconds, thinkingSeconds, codingSeconds, debuggingSeconds,
                    attemptNotes, perceivedDifficulty, solvedWith, actualTopic, approachNotes);
        }
    }

    private static final class AnalysisContext {
        final WorkbookValidationResult validation;
        final WorkbookProfile profile;
        final Map<String, ProblemAccumulator> problemsByKey = new LinkedHashMap<>();
        final List<AnalyzedRoadmapMembership> memberships = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<TrainingSheetDiagnostic> diagnostics = new ArrayList<>();
        final Map<RoadmapStage, Map<Integer, String>> slotOwnersByStage = new EnumMap<>(RoadmapStage.class);
        final Map<RoadmapStage, Integer> detectedRowCountByStage = new EnumMap<>(RoadmapStage.class);

        final Map<RoadmapStage, Integer> stageMembershipCounts = new EnumMap<>(RoadmapStage.class);
        int hyperlinksFound;
        int hyperlinksMissing;
        final Map<String, Integer> platformCounts = new LinkedHashMap<>();
        int explicitPlatformCount;
        int inferredPlatformCount;
        int unknownPlatformCount;
        int solvedCount;
        int inProgressCount;
        int revisitCount;
        int notStartedCount;
        final Map<String, Integer> topicCounts = new LinkedHashMap<>();
        int qualityMetadataCount;
        int suggestedLevelMetadataCount;
        int assistanceMetadataCount;
        final Map<String, Integer> rowsSkippedByReason = new LinkedHashMap<>();
        int duplicateRowsSkipped;
        int invalidRows;

        AnalysisContext(WorkbookValidationResult validation, WorkbookProfile profile) {
            this.validation = validation;
            this.profile = profile;
            for (RoadmapStage stage : RoadmapStage.values()) {
                stageMembershipCounts.put(stage, 0);
            }
            warnings.addAll(validation.structuralWarnings());
            diagnostics.addAll(validation.diagnostics());
        }

        void mergeSkippedReasons(Map<String, Integer> reasons) {
            reasons.forEach((reason, count) -> rowsSkippedByReason.merge(reason, count, Integer::sum));
        }

        /** Records one row/sheet-level finding as both a plain-text warning (for
         *  {@link TrainingSheetImportSummary#warnings()}) and a structured {@link TrainingSheetDiagnostic}
         *  the review screen can render as a table (#160). Every finding recorded here is
         *  {@code WARNING} severity: it means one row or sheet was skipped, never that the whole
         *  import is blocked. */
        void warn(String sheet, Integer row, String column, String reason) {
            TrainingSheetDiagnostic diagnostic = new TrainingSheetDiagnostic(sheet, row, column, reason, TrainingSheetDiagnosticSeverity.WARNING);
            diagnostics.add(diagnostic);
            warnings.add(diagnostic.describe());
        }

        AnalyzedTrainingWorkbook toAnalyzedWorkbook(String workbookName, String fileFingerprint) {
            List<AnalyzedProblem> problems = problemsByKey.entrySet().stream()
                    .map(entry -> entry.getValue().toAnalyzedProblem(entry.getKey()))
                    .toList();
            int attemptSnapshotsFound = (int) problems.stream().filter(problem -> problem.submissionResult() != null).count();
            int problemsWithReflectionMetadata = (int) problems.stream()
                    .filter(problem -> problem.perceivedDifficulty() != null || problem.solvedWith() != null
                            || problem.actualTopic() != null || problem.approachNotes() != null)
                    .count();
            List<TrainingSheetStageSummary> stageSummaries = new ArrayList<>();
            for (RoadmapStage stage : RoadmapStage.values()) {
                int detected = detectedRowCountByStage.getOrDefault(stage, 0);
                int valid = stageMembershipCounts.getOrDefault(stage, 0);
                int skipped = Math.max(0, detected - valid);
                stageSummaries.add(new TrainingSheetStageSummary(stage, detected, valid, skipped, valid));
            }
            WorkbookPreviewDetails details = new WorkbookPreviewDetails(profile, problems.size(), memberships.size(),
                    Map.copyOf(stageMembershipCounts), List.copyOf(stageSummaries), hyperlinksFound, hyperlinksMissing,
                    Map.copyOf(platformCounts), explicitPlatformCount, inferredPlatformCount, unknownPlatformCount,
                    solvedCount, inProgressCount, revisitCount, notStartedCount, Map.copyOf(topicCounts),
                    qualityMetadataCount, suggestedLevelMetadataCount, assistanceMetadataCount,
                    attemptSnapshotsFound, problemsWithReflectionMetadata,
                    Map.copyOf(rowsSkippedByReason), duplicateRowsSkipped, invalidRows,
                    List.copyOf(validation.recognizedSheets()), List.copyOf(validation.ignoredSheets()),
                    List.copyOf(validation.missingRoadmapSheets()));
            return new AnalyzedTrainingWorkbook(workbookName, problems, List.copyOf(memberships), details,
                    List.copyOf(diagnostics), fileFingerprint);
        }
    }
}
