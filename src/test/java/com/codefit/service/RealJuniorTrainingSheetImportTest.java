package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for #159 against the one approved real workbook fixture (see
 * {@code WorkbookContentPolicyTest} and {@code docs/junior-training-sheet-fixture.md}) — the exact
 * file the product must import successfully end to end, not a synthetic stand-in.
 *
 * <p>Assertions key off the roadmap entries stamped with a given run's own {@code import_batch_id}
 * (see {@code RoadmapEntryRepository#findByImportBatchId} and
 * {@code TrainingSheetImportService#importRoadmapSheet}'s unconditional {@code updateImportBatchId}
 * call), rather than the summary's created/reused deltas. Every row a run touches — whether it
 * created a new problem or reused one from an earlier run against the local `codefit.db` dev
 * database this suite shares with every other test — gets re-stamped with that run's batch id, so
 * these counts are exactly "what this run's workbook contains" regardless of whether this is the
 * first time this fixture has ever been imported in this environment.
 *
 * <p>{@code codefit.db} is shared, uncleared state across every test in this suite (e.g.
 * {@code ProblemServiceTest} hard-codes low roadmap slots like stage A position 1). This class
 * deletes every import batch it creates in {@link #removeEveryRoadmapEntryThisClassImported} so the
 * real workbook's ~926 roadmap memberships never permanently occupy those low-numbered slots for
 * other tests — the imported {@code Problem}/{@code ProblemProgress}/{@code ProblemAttempt} rows are
 * left in place (harmless leftover catalog/progress data with no positional uniqueness constraint).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealJuniorTrainingSheetImportTest {

    private static final Path WORKBOOK_PATH = Paths.get("data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx");

    private final TrainingSheetImportService importService = new TrainingSheetImportService();
    private final ProblemRepository problemRepository = new ProblemRepository();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressRepository progressRepository = new ProblemProgressRepository();
    private final ProblemAttemptRepository attemptRepository = new ProblemAttemptRepository();
    private final Set<Long> importBatchIdsToCleanUp = new LinkedHashSet<>();

    @BeforeAll
    void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @AfterAll
    void removeEveryRoadmapEntryThisClassImported() {
        for (Long batchId : importBatchIdsToCleanUp) {
            importService.deleteImportBatch(batchId);
        }
    }

    private TrainingSheetImportSummary importAndTrack() throws Exception {
        TrainingSheetImportSummary summary = importService.importWorkbook(WORKBOOK_PATH);
        importBatchIdsToCleanUp.add(summary.importBatchId());
        return summary;
    }

    @Test
    void validatesAndImportsTheApprovedWorkbookEndToEnd() throws Exception {
        WorkbookValidationResult validation = importService.validate(WORKBOOK_PATH);
        assertTrue(validation.valid(), "the approved workbook must pass structural validation: " + validation.structuralWarnings());
        assertTrue(validation.missingRoadmapSheets().isEmpty(), "every roadmap stage sheet A..D3 is present in the approved workbook");

        TrainingSheetImportSummary summary = importAndTrack();
        assertFalse(summary.dryRun());

        List<RoadmapEntry> entries = roadmapEntryRepository.findByImportBatchId(summary.importBatchId());
        assertEquals(926, entries.size(), "926 roadmap memberships across all seven stages");

        long stageBCount = entries.stream().filter(entry -> entry.getStage() == RoadmapStage.B).count();
        assertEquals(172, stageBCount, "all 172 Stage B problems");

        Set<Long> uniqueProblemIds = entries.stream().map(RoadmapEntry::getProblemId).collect(Collectors.toSet());
        assertEquals(923, uniqueProblemIds.size(), "923 unique real problems");

        WorkbookPreviewDetails details = summary.details();
        assertEquals(172, details.stageMembershipCounts().get(RoadmapStage.B), "per-stage breakdown matches Stage B's real count");
        assertEquals(926, details.stageMembershipCounts().values().stream().mapToInt(Integer::intValue).sum(),
                "per-stage breakdown sums to the same 926 total the preview screen (#160) shows");
        assertTrue(details.hyperlinksFound() > 900, "nearly every real problem carries a recovered judge URL");
        assertTrue(details.platformCounts().getOrDefault("Codeforces", 0) > 500, "Codeforces dominates the inferred platforms");
        assertTrue(details.rowsSkippedByReason().getOrDefault("sample placeholder row", 0) >= 5,
                "the five literal sample rows are reported as a skip reason, not silently vanished");

        // No instructional/sample rows became problems.
        for (Long problemId : uniqueProblemIds) {
            Problem problem = problemRepository.findById(problemId).orElseThrow();
            assertFalse(problem.getTitle().matches("(?i)^Sample (Name|Link)\\d*$"),
                    "a sample placeholder row must never be imported as a real problem: " + problem.getTitle());
            assertFalse(problem.getExternalCode().toLowerCase(java.util.Locale.ROOT).contains("average"),
                    "the 'AC Averages =>' summary row must never be imported as a real problem");
        }
    }

    @Test
    void previewAndRealImportProduceMatchingCounts() throws Exception {
        // #160: the preview ("Analyze workbook") screen must show exactly what a real import would
        // do, since both run through TrainingSheetImportService#runImport's identical row-by-row pass.
        TrainingSheetImportSummary preview = importService.preview(WORKBOOK_PATH);
        assertTrue(preview.dryRun());
        TrainingSheetImportSummary realImport = importAndTrack();

        assertEquals(preview.details().stageMembershipCounts(), realImport.details().stageMembershipCounts());
        assertEquals(preview.details().hyperlinksFound(), realImport.details().hyperlinksFound());
        assertEquals(preview.details().hyperlinksMissing(), realImport.details().hyperlinksMissing());
        assertEquals(preview.details().platformCounts(), realImport.details().platformCounts());
        assertEquals(preview.details().solvedCount(), realImport.details().solvedCount());
        assertEquals(preview.details().rowsSkippedByReason(), realImport.details().rowsSkippedByReason());
    }

    @Test
    void reimportingTheApprovedWorkbookIsFullyIdempotent() throws Exception {
        importAndTrack();
        TrainingSheetImportSummary second = importAndTrack();

        List<RoadmapEntry> entriesTouchedBySecondRun = roadmapEntryRepository.findByImportBatchId(second.importBatchId());
        assertEquals(926, entriesTouchedBySecondRun.size(), "a repeat import still touches (reuses) all 926 memberships");
        assertEquals(0, second.problemsCreated(), "no new problems on a repeat import");
        assertEquals(0, second.roadmapMembershipsCreated(), "no new roadmap memberships on a repeat import");
    }

    @Test
    void embeddedJudgeUrlsAndPlatformsAreInferredFromCodeAndHyperlink() throws Exception {
        importAndTrack();

        Problem codeforcesProblem = problemRepository.findByPlatformAndExternalCode("Codeforces", "CF677-D2-A").orElseThrow();
        assertEquals("Vanya and Fence", codeforcesProblem.getTitle());
        assertTrue(codeforcesProblem.getUrl() != null && codeforcesProblem.getUrl().contains("codeforces.com"),
                "the Codeforces judge URL embedded as a =HYPERLINK(...) formula must be preserved");

        // A native (non-formula) Excel hyperlink on the code cell, not a =HYPERLINK() formula.
        Problem uvaProblem = problemRepository.findByPlatformAndExternalCode("UVA", "UVA 374").orElseThrow();
        assertTrue(uvaProblem.getUrl() != null && uvaProblem.getUrl().contains("onlinejudge.org"),
                "a native Excel hyperlink on the code cell must be preserved even without a =HYPERLINK() formula");
    }

    @Test
    void phaseTimingsSubmitCountIndependenceAndNotesArePreservedOnFirstImport() throws Exception {
        importAndTrack();

        Problem problem = problemRepository.findByPlatformAndExternalCode("Codeforces", "CF677-D2-A").orElseThrow();

        ProblemProgress progress = progressRepository.findByProblemId(problem.getId()).orElseThrow();
        assertEquals(ProblemState.SOLVED, progress.getState());
        assertEquals(2, progress.getPerceivedDifficultyRating());
        assertEquals(SolvedWith.SELF, progress.getSolvedWith(), "'Yes' under 'By yourself?' means SELF");
        assertEquals("String", progress.getActualTopic());
        assertEquals("C++ Solution Example", progress.getApproachNotes());

        List<ProblemAttempt> attempts = attemptRepository.findByProblemId(problem.getId());
        assertEquals(1, attempts.size(), "one imported attempt snapshot, not a fabricated per-submission history");
        ProblemAttempt attempt = attempts.get(0);
        assertEquals(SubmissionResult.AC, attempt.submissionResult());
        assertEquals(1, attempt.attemptNumber(), "Submit Count of 1");
        assertEquals(120, attempt.readingTimeSeconds(), "2 reading minutes -> 120 seconds");
        assertEquals(60, attempt.thinkingTimeSeconds(), "1 thinking minute -> 60 seconds");
        assertEquals(1500, attempt.codingTimeSeconds(), "25 coding minutes -> 1500 seconds");
        assertEquals(0, attempt.debuggingTimeSeconds());
    }

    @Test
    void existingRealAcRecordsSurviveReImportUntouched() throws Exception {
        importAndTrack();
        // A different real problem than the other tests in this class target, so mutating its
        // progress here can never race with (or be seen by) another test method sharing this suite's
        // persistent `codefit.db` — JUnit does not guarantee method execution order.
        Problem problem = problemRepository.findByPlatformAndExternalCode("Codeforces", "CF734-D2-A").orElseThrow();

        // Simulate the learner already having recorded independent progress locally beyond what the
        // workbook itself carries, exactly like `ProblemProgressServiceTest`'s non-destructive-import
        // coverage but against a real imported problem rather than a synthetic one.
        new ProblemProgressService().updateReflection(problem.getId(), new ProblemReflection(
                9, com.codefit.model.SolvedWith.SOLUTION, com.codefit.model.FinalCategory.WEAK,
                "the learner's own later approach notes", null, null, null, null, null,
                "the learner's own later topic", false, false, false, false));

        importAndTrack();

        ProblemProgress progress = progressRepository.findByProblemId(problem.getId()).orElseThrow();
        assertEquals(ProblemState.SOLVED, progress.getState(), "already-SOLVED state is never downgraded by a re-import");
        assertEquals(9, progress.getPerceivedDifficultyRating(), "the learner's own later rating is never overwritten by the workbook's");
        assertEquals(com.codefit.model.SolvedWith.SOLUTION, progress.getSolvedWith());
        assertEquals("the learner's own later approach notes", progress.getApproachNotes());
        assertEquals("the learner's own later topic", progress.getActualTopic());
    }
}
