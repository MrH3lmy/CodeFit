package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ComplexityClass;
import com.codefit.model.FinalCategory;
import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.JavaSolutionDraft;
import com.codefit.model.JavaTestCase;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvedWith;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemGuidanceRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.ProblemSolvingSessionRepository;
import com.codefit.repository.RoadmapEntryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Epic #164's required end-to-end regression: the complete import-to-practice critical path against
 * one fully isolated, throwaway SQLite database (see {@link DatabaseConfig#useDatabaseFile}) rather
 * than the shared local {@code codefit.db} every other integration test in this suite touches — this
 * class never depends on, and can never corrupt, a developer's real database. Every step below calls
 * the same public service APIs the UI controllers call; nothing here re-implements import parsing,
 * recommendation selection, hint-ladder, or Java-runner logic.
 *
 * <p><b>Why this test targets three different problems.</b> The approved workbook fixture is a real
 * learner's actual workbook: its first 10 Stage A rows (by design — see {@code StageAPilotGuidanceSeed}'s
 * class docs) are exactly the ones already recorded {@code SOLVED} in that real historical data, which
 * is also why they were chosen as the #171 pilot-guidance set (issue #159's "existing 10 real AC
 * records are preserved" acceptance criterion refers to these same 10 rows). That means the actual
 * next <em>recommended</em> problem after import is Stage A's 11th row, not the pilot problem — so
 * this test uses the pilot problem ({@code CF677-D2-A}) only for a direct, read-only guidance/provenance
 * check (step 13), and drives the full guided-session/hint/Java-runner/finish/revisit journey (steps
 * 6-24) against the actual first untouched recommended problem instead, exactly like a real learner
 * would experience it.
 *
 * <p>Steps are numbered to match the epic's acceptance walkthrough and run in declared order (one
 * shared learner journey against one shared database) since later steps depend on earlier ones having
 * already imported the curriculum and advanced its state — this mirrors how a real learner session
 * actually unfolds, rather than each step re-deriving its own fixture from scratch.
 *
 * <p>Restart is simulated the same way {@code RealJuniorTrainingSheetImportTest} and every other
 * repository-backed test in this suite implicitly rely on: every repository/service call opens a
 * fresh JDBC connection and no production class caches state in memory across calls, so constructing
 * a brand-new service/repository instance and reading back through it (rather than reusing the
 * instance a previous step already had a reference to) genuinely proves persistence rather than an
 * in-memory field surviving by accident.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportToPracticeCriticalPathTest {

    private static final Path WORKBOOK_PATH = Paths.get("data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx");

    private static long importBatchId;
    /** {@code CF677-D2-A}: Stage A's first pilot-guidance problem, already SOLVED in the workbook's own historical data. */
    private static long pilotProblemId;
    /** {@code CF381-D2-A}: Stage A's first genuinely untouched row - the real "next recommended problem". */
    private static long workingProblemId;
    /** {@code CF266-D2-A}: Stage A's next untouched row after {@link #workingProblemId} is solved. */
    private static long secondWorkingProblemId;

    @BeforeAll
    void useIsolatedDatabase(@TempDir Path tempDir) {
        // Step 1: start from a clean, isolated test database - never the shared local codefit.db.
        DatabaseConfig.useDatabaseFile(tempDir.resolve("critical-path.db"));
        DatabaseConfig.initialize();
    }

    @AfterAll
    void restoreSharedDatabase() {
        DatabaseConfig.useDefaultDatabaseFile();
    }

    @Test
    @Order(1)
    void analyzingTheWorkbookNeverMutatesTheDatabase() {
        ProblemRepository problemRepository = new ProblemRepository();
        // DatabaseConfig.initialize() seeds the Stage A pilot guidance's 10 problems/roadmap rows
        // (#171) unconditionally on every startup, so "clean" here means "only the seed", not zero.
        int countBeforeAnalyze = problemRepository.countAll();
        assertEquals(10, countBeforeAnalyze, "only the Stage A pilot guidance seed exists before any workbook import");

        TrainingSheetImportService importService = new TrainingSheetImportService();

        // Step 2: analyze the approved workbook - pure, in-memory, no database connection at all.
        AnalyzedTrainingWorkbook analyzed = importService.analyze(WORKBOOK_PATH);

        assertEquals(countBeforeAnalyze, problemRepository.countAll(), "analyze() must never write to the database");
        assertFalse(analyzed.hasBlockingDiagnostics(), "the approved workbook has no blocking errors");

        // Step 3: verify preview metadata and expected import counts before any confirmation.
        TrainingSheetImportSummary preview = importService.previewOf(analyzed);
        assertTrue(preview.dryRun());
        assertEquals(923, preview.details().uniqueProblemCount(), "923 unique real problems");
        assertEquals(926, preview.details().roadmapMembershipCount(), "926 roadmap memberships");
        assertEquals(172, preview.details().stageMembershipCounts().get(RoadmapStage.B), "172 Stage B memberships");
        assertEquals(countBeforeAnalyze, problemRepository.countAll(), "previewing must never write to the database either");

        // Step 4: confirm and import the exact analyzed snapshot transactionally.
        TrainingSheetImportSummary imported = importService.importAnalyzed(analyzed, ImportSourceMetadata.unspecified());
        assertFalse(imported.dryRun());
        importBatchId = imported.importBatchId();

        // Step 5: verify the product's exact expected counts post-import.
        RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
        List<RoadmapEntry> entriesFromThisImport = roadmapEntryRepository.findByImportBatchId(importBatchId);
        assertEquals(926, entriesFromThisImport.size(), "926 roadmap memberships across all seven stages");
        long stageBCount = entriesFromThisImport.stream().filter(entry -> entry.getStage() == RoadmapStage.B).count();
        assertEquals(172, stageBCount, "all 172 Stage B problems");
        Set<Long> uniqueProblemIds = entriesFromThisImport.stream().map(RoadmapEntry::getProblemId).collect(Collectors.toSet());
        assertEquals(923, uniqueProblemIds.size(), "923 unique real problems");

        // The Stage A pilot seed's 10 problems merge into these same rows rather than duplicating
        // (#174's documented identity contract) - importing must never grow past 923 unique problems.
        assertEquals(923, problemRepository.countAll(), "the pilot seed's 10 problems merge into the import, not duplicate alongside it");

        pilotProblemId = problemRepository.findByPlatformAndExternalCode("Codeforces", "CF677-D2-A").orElseThrow().getId();
    }

    @Test
    @Order(2)
    void reimportingTheSameWorkbookIsIdempotentAndCreatesNoDuplicates() {
        TrainingSheetImportService importService = new TrainingSheetImportService();
        ProblemRepository problemRepository = new ProblemRepository();

        TrainingSheetImportSummary second = importService.importWorkbook(WORKBOOK_PATH);
        // A re-import unconditionally re-stamps every roadmap entry it touches with its own fresh
        // import_batch_id (see TrainingSheetImportService#applyMembership), so this run's batch id -
        // not Order(1)'s original one - is now the one every entry actually carries.
        importBatchId = second.importBatchId();

        assertEquals(0, second.problemsCreated(), "no new problems on a repeat import");
        assertEquals(0, second.roadmapMembershipsCreated(), "no new roadmap memberships on a repeat import");
        assertEquals(923, problemRepository.countAll(), "re-importing must never duplicate problem rows");

        RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
        assertEquals(926, roadmapEntryRepository.findAllInRoadmapOrder().size(),
                "re-importing must never duplicate roadmap membership rows");
    }

    @Test
    @Order(3)
    void seededCodeFitGuidanceForTheStageAPilotProblemIsAvailableWithCorrectProvenance() {
        // Step 13 (checked early since it needs no session): load the seeded CodeFit guidance for a
        // Stage A pilot problem and verify provenance survived the import merge untouched.
        ProblemGuidanceRepository guidanceRepository = new ProblemGuidanceRepository();
        ProblemGuidance guidance = guidanceRepository.findByProblemIdAndSource(pilotProblemId, GuidanceSource.CODEFIT)
                .orElseThrow(() -> new AssertionError("the Stage A pilot seed must have attached CODEFIT guidance to this problem"));

        assertEquals(GuidanceSource.CODEFIT, guidance.getSource());
        assertTrue(guidance.hasCompleteExplanation(), "the pilot set's explanation must cover idea, pseudocode, complexity, and mistakes");
        assertTrue(guidance.textForLevel(HintLevel.CLARIFY) != null && !guidance.textForLevel(HintLevel.CLARIFY).isBlank());

        // The active guidance resolved through the learner-override-aware lookup must be the same
        // CODEFIT content, since this problem has no learner override - the import merge (#159/#174)
        // must never have overwritten or shadowed it with a blank/learner row.
        Optional<ProblemGuidance> activeGuidance = new ProblemGuidanceService().getGuidance(pilotProblemId);
        assertTrue(activeGuidance.isPresent());
        assertEquals(GuidanceSource.CODEFIT, activeGuidance.get().getSource(),
                "importing the workbook must never overwrite the pilot's CodeFit-authored guidance");

        // This same real workbook already recorded this exact problem SOLVED (independently, by the
        // workbook's own author) - #159's "existing 10 real AC records are preserved" acceptance
        // criterion, preserved by the import rather than reset to NOT_STARTED.
        ProblemProgress progress = new ProblemProgressService().getOrCreate(pilotProblemId);
        assertEquals(ProblemState.SOLVED, progress.getState(), "the workbook's own pre-existing solve must survive import");
    }

    @Test
    @Order(4)
    void nextRecommendedCurriculumProblemIsAvailableAndIsTheFirstGenuinelyUntouchedRow() {
        // Step 6: the next recommended problem must be available immediately after import. Stage A's
        // first 10 rows are already SOLVED in the workbook's own real historical data (see this
        // class's docs and the previous test), so the true recommendation is the 11th row.
        ProblemLibraryService libraryService = new ProblemLibraryService();
        Optional<ProblemLibraryEntry> nextRecommended = libraryService.getNextRecommendedProblem();

        assertTrue(nextRecommended.isPresent(), "a freshly imported curriculum must always have a next recommended problem");
        Problem recommended = nextRecommended.get().problem();
        assertEquals("Codeforces", recommended.getPlatform());
        assertEquals("CF381-D2-A", recommended.getExternalCode(), "Stage A's first genuinely unsolved row");
        workingProblemId = recommended.getId();

        RoadmapEntry roadmapEntry = nextRecommended.get().roadmapEntry();
        assertEquals(RoadmapStage.A, roadmapEntry.getStage());
        assertEquals(11, roadmapEntry.getSequenceOrder());

        ProblemProgress progress = new ProblemProgressService().getOrCreate(workingProblemId);
        assertEquals(ProblemState.NOT_STARTED, progress.getState(), "the recommended problem must be genuinely untouched");
    }

    @Test
    @Order(5)
    void guidedSessionTracksAllFourSolvingPhasesAndSurvivesAReload() {
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();

        // Step 7: start a structured problem-solving session.
        ProblemSolvingSession started = workspaceService.start(workingProblemId);
        assertEquals(SolvingPhase.READING, started.getPhase());
        assertTrue(started.isActive());
        assertFalse(started.isPaused());

        // Step 8: exercise phase tracking across all four phases.
        workspaceService.tick(workingProblemId, SolvingPhase.READING, 120);
        workspaceService.tick(workingProblemId, SolvingPhase.THINKING, 60);
        workspaceService.tick(workingProblemId, SolvingPhase.CODING, 300);
        workspaceService.tick(workingProblemId, SolvingPhase.DEBUGGING, 30);

        // Step 9: verify the session and elapsed phase data survive a repository/service reload -
        // fresh instances, not the ones the session was built with above.
        ProblemSolvingSessionRepository reloadedSessionRepository = new ProblemSolvingSessionRepository();
        ProblemSolvingSession reloaded = reloadedSessionRepository.findByProblemId(workingProblemId).orElseThrow();
        assertEquals(SolvingPhase.DEBUGGING, reloaded.getPhase());
        assertEquals(120, reloaded.getReadingSecondsElapsed());
        assertEquals(60, reloaded.getThinkingSecondsElapsed());
        assertEquals(300, reloaded.getCodingSecondsElapsed());
        assertEquals(30, reloaded.getDebuggingSecondsElapsed());
    }

    @Test
    @Order(6)
    void progressiveHintLadderOpensOneLevelAtATimeWithoutFabricatingMissingContent() {
        ProblemGuidanceService guidanceService = new ProblemGuidanceService();

        // Step 10: reveal the ladder one level at a time - never skipping ahead. This working
        // problem (unlike the pilot problem checked above) has no authored guidance at all yet - the
        // mechanism must still track levels correctly and must report "no content" honestly rather
        // than fabricating text (#162's "missing guidance is handled clearly without fabricating
        // authoritative content" principle).
        ProblemGuidanceService.HintReveal clarify = guidanceService.openNextHintLevel(workingProblemId);
        assertEquals(HintLevel.CLARIFY, clarify.level());
        assertFalse(clarify.hasContent());
        assertNull(clarify.text());

        ProblemGuidanceService.HintReveal observation = guidanceService.openNextHintLevel(workingProblemId);
        assertEquals(HintLevel.OBSERVATION, observation.level());
        assertFalse(observation.hasContent());

        // Step 11: the highest opened level is persisted - verified via a fresh service instance.
        ProblemGuidanceService reloadedGuidanceService = new ProblemGuidanceService();
        assertEquals(Optional.of(HintLevel.OBSERVATION), reloadedGuidanceService.getOpenedLevel(workingProblemId));

        // Step 12: the assistance level implied by this attempt's hint usage is HINT, not SELF
        // (nothing opened) or EDITORIAL (the full Explanation was never opened this attempt).
        assertEquals(SolvedWith.HINT, reloadedGuidanceService.computeAssistanceLevel(HintLevel.OBSERVATION));
    }

    @Test
    @Order(7)
    void javaDraftSurvivesAReloadAndCompilesRunsAndPassesALocalTestCase() {
        JavaSolutionWorkspaceService javaWorkspaceService = new JavaSolutionWorkspaceService();
        String source = """
                import java.util.Scanner;
                public class Solution {
                    public static void main(String[] args) {
                        Scanner scanner = new Scanner(System.in);
                        int a = scanner.nextInt();
                        int b = scanner.nextInt();
                        System.out.println(a + b);
                    }
                }
                """;

        // Step 14: save a Java solution draft.
        javaWorkspaceService.saveDraft(workingProblemId, "Solution", source, "3 4\n", "7");

        // Step 15: reload it (fresh service instance) and verify the draft survives.
        JavaSolutionWorkspaceService reloadedWorkspaceService = new JavaSolutionWorkspaceService();
        JavaSolutionDraft reloadedDraft = reloadedWorkspaceService.loadDraft(workingProblemId);
        assertEquals("Solution", reloadedDraft.getMainClassName());
        assertEquals(source, reloadedDraft.getSourceCode());
        assertEquals("3 4\n", reloadedDraft.getStdin());
        assertEquals("7", reloadedDraft.getExpectedOutput());

        // Step 16: compile and run the draft, only when this CI environment has a compatible JDK -
        // the same guard JavaCodeRunnerTest and JavaCodeRunner#isAvailable() already establish.
        assumeTrue(reloadedWorkspaceService.isRunnerAvailable(),
                "skipping compile/run: " + reloadedWorkspaceService.getRunnerUnavailabilityReason());

        try (CompileOutcome compiled = reloadedWorkspaceService.compile(reloadedDraft.getSourceCode(), reloadedDraft.getMainClassName())) {
            assertTrue(compiled.success(), compiled.rawOutput());
            RunResult quickRun = reloadedWorkspaceService.run(compiled, reloadedDraft.getStdin(), RunLimits.defaults(), null);
            assertFalse(quickRun.timedOut());
            assertFalse(quickRun.cancelled());
            assertEquals(0, quickRun.exitCode());
            assertTrue(quickRun.matchesExpectedOutput(reloadedDraft.getExpectedOutput()));

            // Step 17: run at least one persisted local test case against the same compiled outcome.
            JavaTestCase testCase = reloadedWorkspaceService.addTestCase(workingProblemId);
            reloadedWorkspaceService.updateTestCase(testCase.getId(), workingProblemId, testCase.getPosition(), "3 4\n", "7");
            JavaTestCase savedTestCase = reloadedWorkspaceService.listTestCases(workingProblemId).get(0);

            RunResult testCaseRun = reloadedWorkspaceService.runTestCase(compiled, savedTestCase, RunLimits.defaults(), null);
            assertEquals(Boolean.TRUE, savedTestCase.matches(testCaseRun.stdout()), "the local test case must PASS against the compiled draft");
        }
    }

    @Test
    @Order(8)
    void finishingWithAnExternalJudgeVerdictRecordsTheAttemptAndReflection() {
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();

        // Steps 18-19: recording the external judge's verdict IS how this workspace finishes a
        // session (see ProblemSolvingWorkspaceService#finish's class docs) - the external judge stays
        // authoritative, and there is deliberately no separate "record verdict" step from "finish".
        Optional<ProblemAttempt> attempt = workspaceService.finish(workingProblemId, SessionFinishOutcome.ACCEPTED, SubmissionResult.AC,
                "solved after opening the Observation hint");
        assertTrue(attempt.isPresent());
        assertEquals(SubmissionResult.AC, attempt.get().submissionResult());
        assertEquals(1, attempt.get().attemptNumber());

        // Step 20: save post-solve reflection data (finish() already inferred the assistance level;
        // this only adds the learner's own reflection fields on top, exactly like the workspace UI's
        // separate reflection step - see ProblemSolvingWorkspaceService#markPreviouslySolved for the
        // same read-then-extend pattern).
        ProblemProgress beforeReflection = new ProblemProgressService().getOrCreate(workingProblemId);
        assertEquals(SolvedWith.HINT, beforeReflection.getSolvedWith(),
                "finish() must infer HINT from the Observation-level hint opened during this attempt");
        workspaceService.updateReflection(workingProblemId, new ProblemReflection(
                7, beforeReflection.getSolvedWith(), FinalCategory.STRONG, beforeReflection.getApproachNotes(),
                "forgot to flush output once", "the two integers can be summed directly with no edge case",
                ComplexityClass.O_N, ComplexityClass.O_1, "reading with Scanner is enough, no need to overthink it",
                beforeReflection.getActualTopic(), true, false, false, false));

        // Step 21: verify progress, attempt count, timings, assistance, and reflection data together,
        // all through fresh repository/service instances.
        ProblemProgress reloadedProgress = new ProblemProgressService().getOrCreate(workingProblemId);
        assertEquals(ProblemState.SOLVED, reloadedProgress.getState());
        assertEquals(SolvedWith.HINT, reloadedProgress.getSolvedWith(), "the reflection save must not clobber the inferred assistance level");
        assertEquals(FinalCategory.STRONG, reloadedProgress.getFinalCategory());
        assertEquals(7, reloadedProgress.getPerceivedDifficultyRating());
        assertEquals(ComplexityClass.O_N, reloadedProgress.getTimeComplexity());
        assertEquals(ComplexityClass.O_1, reloadedProgress.getSpaceComplexity());
        assertEquals("forgot to flush output once", reloadedProgress.getMistakeNotes());
        assertTrue(reloadedProgress.isEditorialUnderstood());

        List<ProblemAttempt> attempts = new ProblemAttemptRepository().findByProblemId(workingProblemId);
        assertEquals(1, attempts.size(), "this working problem had no prior attempts before this session");
        ProblemAttempt recorded = attempts.get(0);
        assertEquals(120, recorded.readingTimeSeconds());
        assertEquals(60, recorded.thinkingTimeSeconds());
        assertEquals(300, recorded.codingTimeSeconds());
        assertEquals(30, recorded.debuggingTimeSeconds());

        // Finishing always resets the in-progress session/timer state for the next attempt.
        assertTrue(new ProblemSolvingSessionRepository().findByProblemId(workingProblemId).isEmpty(),
                "finish() must reset the session so a future re-attempt starts its own timers from zero");
    }

    @Test
    @Order(9)
    void recommendationAdvancesAndTheHintDependentSolveStaysInTheRevisitQueue() {
        // Step 22: the recommendation must advance now that the first untouched Stage A slot is SOLVED.
        ProblemLibraryService libraryService = new ProblemLibraryService();
        Optional<ProblemLibraryEntry> next = libraryService.getNextRecommendedProblem();
        assertTrue(next.isPresent());
        assertNotEquals(workingProblemId, next.get().problem().getId(), "the recommendation must move past the just-solved problem");
        assertEquals("CF266-D2-A", next.get().problem().getExternalCode(), "Stage A's next genuinely untouched row");
        secondWorkingProblemId = next.get().problem().getId();

        // Step 23: a hint-dependent solve stays SOLVED but must remain visible through revisit -
        // solved-with-help problems are supplemental follow-up, not roadmap regressions.
        List<ProblemLibraryEntry> revisitQueue = libraryService.getRevisitQueue();
        assertTrue(revisitQueue.stream().anyMatch(entry -> entry.problem().getId() == workingProblemId),
                "a HINT-assisted solved problem must still appear in the revisit queue");
    }

    @Test
    @Order(10)
    void aFailedAttemptMovesToNeedsRevisitWithoutStallingTheRecommendation() {
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        workspaceService.start(secondWorkingProblemId);
        workspaceService.tick(secondWorkingProblemId, SolvingPhase.READING, 90);
        workspaceService.tick(secondWorkingProblemId, SolvingPhase.CODING, 600);

        // Recording a failed external-judge verdict (Could Not Solve / WA).
        Optional<ProblemAttempt> attempt = workspaceService.finish(secondWorkingProblemId, SessionFinishOutcome.COULD_NOT_SOLVE,
                SubmissionResult.WA, "off-by-one on the boundary case");
        assertTrue(attempt.isPresent());

        ProblemProgress progress = new ProblemProgressService().getOrCreate(secondWorkingProblemId);
        assertEquals(ProblemState.NEEDS_REVISIT, progress.getState());

        ProblemLibraryService libraryService = new ProblemLibraryService();
        assertTrue(libraryService.getRevisitQueue().stream().anyMatch(entry -> entry.problem().getId() == secondWorkingProblemId),
                "a failed attempt must be visible through the revisit queue");

        // The recommendation must advance past the failed-but-still-open position to untouched
        // mandatory curriculum work, rather than trapping the learner on the same failed problem.
        Optional<ProblemLibraryEntry> next = libraryService.getNextRecommendedProblem();
        assertTrue(next.isPresent());
        assertNotEquals(secondWorkingProblemId, next.get().problem().getId(),
                "the recommendation must not get stuck on a NEEDS_REVISIT position");
        assertEquals("CF427-D2-A", next.get().problem().getExternalCode());
    }

    @Test
    @Order(11)
    void everyStepsPersistedStateSurvivesASimulatedApplicationRestart() {
        // Step 24: simulate a full application restart by re-initializing against the very same
        // database file (exactly what CodeFitApplication#start does on every real launch) and
        // re-reading everything through brand-new repository/service instances.
        DatabaseConfig.initialize();

        RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
        assertEquals(926, roadmapEntryRepository.findByImportBatchId(importBatchId).size(),
                "the imported roadmap must still exist after a simulated restart");

        JavaSolutionDraft draft = new JavaSolutionWorkspaceService().loadDraft(workingProblemId);
        assertTrue(draft.getSourceCode() != null && draft.getSourceCode().contains("Scanner"),
                "the saved Java draft must survive a simulated restart");

        List<JavaTestCase> testCases = new JavaSolutionWorkspaceService().listTestCases(workingProblemId);
        assertEquals(1, testCases.size(), "the saved local test case must survive a simulated restart");

        ProblemProgress workingProblemProgress = new ProblemProgressService().getOrCreate(workingProblemId);
        assertEquals(ProblemState.SOLVED, workingProblemProgress.getState());
        assertEquals(SolvedWith.HINT, workingProblemProgress.getSolvedWith());

        ProblemProgress secondProblemProgress = new ProblemProgressService().getOrCreate(secondWorkingProblemId);
        assertEquals(ProblemState.NEEDS_REVISIT, secondProblemProgress.getState());

        assertEquals(GuidanceSource.CODEFIT, new ProblemGuidanceRepository()
                        .findByProblemIdAndSource(pilotProblemId, GuidanceSource.CODEFIT).orElseThrow().getSource(),
                "seeded CodeFit guidance must survive a simulated restart");

        assertNull(new ProblemSolvingSessionRepository().findByProblemId(workingProblemId).orElse(null),
                "a finished session's timer state must stay reset across a restart, not silently reappear");
    }
}
