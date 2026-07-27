package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the structured solving workspace's finish logic (#145): mapping each of the four finish
 * outcomes onto a {@link ProblemAttempt}/{@link com.codefit.model.ProblemProgress} update (or, for
 * {@code ABANDONED}, onto neither), and that finishing never loses a problem's earlier attempts.
 * Touches the shared local database idempotently, using a fresh unique platform per test.
 */
class ProblemSolvingWorkspaceServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();
    private final ProblemSolvingSessionService sessionService = new ProblemSolvingSessionService();
    private final ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private long fixtureProblemId(String testName) {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-WORKSPACE", testName + "-" + UUID.randomUUID(),
                "Workspace Fixture", null, "General", null, List.of());
        return problem.getId();
    }

    @Test
    void loadWorkspaceReturnsTheProblemItsProgressAndItsCurrentSession() {
        long problemId = fixtureProblemId("load");
        sessionService.recordElapsedTime(problemId, SolvingPhase.READING, 10);

        ProblemSolvingWorkspaceService.WorkspaceView view = workspaceService.loadWorkspace(problemId);

        assertEquals(problemId, view.problem().getId());
        assertEquals(ProblemState.NOT_STARTED, view.progress().getState());
        assertTrue(view.session().isPresent());
        assertEquals(10, view.session().get().getReadingSecondsElapsed());
    }

    @Test
    void finishingAsAcceptedRecordsAnAcAttemptAndMarksTheProblemSolved() {
        long problemId = fixtureProblemId("accepted");
        sessionService.recordElapsedTime(problemId, SolvingPhase.CODING, 120);

        Optional<ProblemAttempt> recorded = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "clean solution");

        assertTrue(recorded.isPresent());
        assertEquals(SubmissionResult.AC, recorded.get().submissionResult());
        assertEquals(SessionFinishOutcome.ACCEPTED, recorded.get().sessionOutcome());
        assertEquals(120, recorded.get().codingTimeSeconds());
        assertEquals(ProblemState.SOLVED, workspaceService.loadWorkspace(problemId).progress().getState());
        assertTrue(sessionService.findSession(problemId).isEmpty(), "finishing resets the session so a future attempt starts fresh");
    }

    @Test
    void finishingAsSubmittedWithANonAcVerdictLeavesTheProblemInProgress() {
        long problemId = fixtureProblemId("submitted-wa");

        Optional<ProblemAttempt> recorded = workspaceService.finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.WA, null);

        assertTrue(recorded.isPresent());
        assertEquals(SubmissionResult.WA, recorded.get().submissionResult());
        assertEquals(SessionFinishOutcome.SUBMITTED, recorded.get().sessionOutcome());
        assertEquals(ProblemState.IN_PROGRESS, workspaceService.loadWorkspace(problemId).progress().getState());
    }

    @Test
    void finishingAsSubmittedWithAnAcVerdictMarksTheProblemSolved() {
        long problemId = fixtureProblemId("submitted-ac");

        workspaceService.finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.AC, null);

        assertEquals(ProblemState.SOLVED, workspaceService.loadWorkspace(problemId).progress().getState());
    }

    @Test
    void finishingAsCouldNotSolveDefaultsToWaAndMarksNeedsRevisit() {
        long problemId = fixtureProblemId("could-not-solve");

        Optional<ProblemAttempt> recorded = workspaceService.finish(problemId, SessionFinishOutcome.COULD_NOT_SOLVE, null, "stuck on edge case");

        assertTrue(recorded.isPresent());
        assertEquals(SubmissionResult.WA, recorded.get().submissionResult());
        assertEquals(SessionFinishOutcome.COULD_NOT_SOLVE, recorded.get().sessionOutcome());
        assertEquals(ProblemState.NEEDS_REVISIT, workspaceService.loadWorkspace(problemId).progress().getState());
    }

    @Test
    void finishingAsAbandonedCreatesNoAttemptAndLeavesProgressUntouched() {
        long problemId = fixtureProblemId("abandoned");
        sessionService.recordElapsedTime(problemId, SolvingPhase.READING, 15);
        int attemptsBefore = attemptService.getAttempts(problemId).size();

        Optional<ProblemAttempt> recorded = workspaceService.finish(problemId, SessionFinishOutcome.ABANDONED, null, null);

        assertTrue(recorded.isEmpty());
        assertEquals(attemptsBefore, attemptService.getAttempts(problemId).size());
        assertEquals(ProblemState.NOT_STARTED, workspaceService.loadWorkspace(problemId).progress().getState());
        // The session isn't deleted outright (in case the learner didn't mean to abandon), just deactivated.
        ProblemSolvingSession session = sessionService.findSession(problemId).orElseThrow();
        assertFalse(session.isActive());
        assertEquals(15, session.getReadingSecondsElapsed());
    }

    @Test
    void finishingMultipleTimesNeverLosesEarlierAttempts() {
        long problemId = fixtureProblemId("multiple-attempts");

        workspaceService.finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.WA, "first try");
        workspaceService.finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.TLE, "second try");
        workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "third try, finally");

        List<ProblemAttempt> attempts = attemptService.getAttempts(problemId);
        assertEquals(3, attempts.size());
        assertEquals(1, attempts.get(0).attemptNumber());
        assertEquals(SubmissionResult.WA, attempts.get(0).submissionResult());
        assertEquals(2, attempts.get(1).attemptNumber());
        assertEquals(SubmissionResult.TLE, attempts.get(1).submissionResult());
        assertEquals(3, attempts.get(2).attemptNumber());
        assertEquals(SubmissionResult.AC, attempts.get(2).submissionResult());
    }

    @Test
    void pauseAndResumeGoThroughTheWorkspaceServiceJustLikeTheSessionService() {
        long problemId = fixtureProblemId("pause-resume");
        workspaceService.start(problemId);
        workspaceService.tick(problemId, SolvingPhase.CODING, 5);

        ProblemSolvingSession paused = workspaceService.pause(problemId);
        assertTrue(paused.isPaused());
        ProblemSolvingSession stillFive = workspaceService.tick(problemId, SolvingPhase.CODING, 5);
        assertEquals(5, stillFive.getCodingSecondsElapsed());

        ProblemSolvingSession resumed = workspaceService.resume(problemId);
        assertFalse(resumed.isPaused());
        ProblemSolvingSession afterResume = workspaceService.tick(problemId, SolvingPhase.CODING, 5);
        assertEquals(10, afterResume.getCodingSecondsElapsed());
    }

    @Test
    void resetClearsTheSessionThroughTheWorkspaceService() {
        long problemId = fixtureProblemId("reset");
        workspaceService.tick(problemId, SolvingPhase.DEBUGGING, 20);

        workspaceService.reset(problemId);

        assertTrue(sessionService.findSession(problemId).isEmpty());
    }
}
