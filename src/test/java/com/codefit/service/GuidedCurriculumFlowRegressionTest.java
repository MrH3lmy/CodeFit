package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the remaining guided-curriculum gaps from #161. */
class GuidedCurriculumFlowRegressionTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemLibraryService libraryService = new ProblemLibraryService();
    private final ProblemSolvingSessionService sessionService = new ProblemSolvingSessionService();
    private final ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
    private final ProblemGuidanceService guidanceService = new ProblemGuidanceService();

    private final Random random = new Random();
    private int nextOrder = 40_000_000 + random.nextInt(1_000_000);

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-GUIDED-FLOW-" + testName + "-" + UUID.randomUUID();
    }

    private Problem createProblem(String platform, String code, String title) {
        return problemService.findOrCreateProblem(platform, code, title,
                "https://example.test/" + code, "General", null, List.of());
    }

    @Test
    void recommendationAdvancesToUntouchedWorkWhileFailedWorkRemainsInTheRevisitQueue() {
        String platform = uniquePlatform("failed-next");
        Problem failed = createProblem(platform, "FN1", "Failed First");
        Problem next = createProblem(platform, "FN2", "Untouched Next");
        int baseOrder = nextOrder;
        nextOrder += 2;
        problemService.addToRoadmap(failed.getId(), RoadmapStage.C1, baseOrder, null, true, null);
        problemService.addToRoadmap(next.getId(), RoadmapStage.C1, baseOrder + 1, null, true, null);

        workspaceService.finish(failed.getId(), SessionFinishOutcome.SUBMITTED, SubmissionResult.WA, "failed attempt");

        List<ProblemLibraryEntry> ours = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();
        Optional<ProblemLibraryEntry> recommended = libraryService.getNextRecommendedProblem(ours);
        List<ProblemLibraryEntry> revisitQueue = libraryService.getRevisitQueue(ours);

        assertEquals(next.getId(), recommended.orElseThrow().problem().getId());
        assertEquals(1, revisitQueue.size());
        assertEquals(failed.getId(), revisitQueue.get(0).problem().getId());
        assertEquals(ProblemState.IN_PROGRESS, revisitQueue.get(0).progress().getState());
    }

    @Test
    void hintDependentSolvedProblemStaysSolvedAndIsScheduledForRevisit() {
        String platform = uniquePlatform("hint-revisit");
        Problem problem = createProblem(platform, "HR1", "Hint Dependent Solve");
        problemService.addToRoadmap(problem.getId(), RoadmapStage.C2, nextOrder++, null, true, null);

        guidanceService.openNextHintLevel(problem.getId());
        workspaceService.finish(problem.getId(), SessionFinishOutcome.ACCEPTED, null, null);

        ProblemProgress progress = workspaceService.loadWorkspace(problem.getId()).progress();
        List<ProblemLibraryEntry> revisitQueue = libraryService.getRevisitQueue().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        assertEquals(ProblemState.SOLVED, progress.getState(), "revisit must not undo roadmap completion");
        assertEquals(SolvedWith.HINT, progress.getSolvedWith());
        assertEquals(1, revisitQueue.size());
        assertEquals(problem.getId(), revisitQueue.get(0).problem().getId());
    }

    @Test
    void explicitOverrideStartsTheExactPersistedProblemSkippedByMandatoryGating() {
        String platform = uniquePlatform("override");
        Problem optionalEarlier = createProblem(platform, "OV1", "Optional Override Target");
        Problem mandatoryLater = createProblem(platform, "OV2", "Mandatory Guided Target");
        int baseOrder = nextOrder;
        nextOrder += 2;
        problemService.addToRoadmap(optionalEarlier.getId(), RoadmapStage.D1, baseOrder, null, false, null);
        problemService.addToRoadmap(mandatoryLater.getId(), RoadmapStage.D1, baseOrder + 1, null, true, null);

        List<ProblemLibraryEntry> ours = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();
        Optional<ProblemLibraryEntry> guided = libraryService.getNextRecommendedProblem(ours);
        assertEquals(mandatoryLater.getId(), guided.orElseThrow().problem().getId());

        ProblemSolvingSession overrideSession = sessionService.startOrResume(optionalEarlier.getId());
        assertEquals(optionalEarlier.getId(), overrideSession.getProblemId());
        assertEquals(mandatoryLater.getId(), libraryService.getNextRecommendedProblem(ours).orElseThrow().problem().getId(),
                "starting an override must not silently rewrite the guided recommendation");

        sessionService.reset(optionalEarlier.getId());
    }
}
