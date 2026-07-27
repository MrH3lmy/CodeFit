package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.SolvingPhase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the persistent, resumable {@link ProblemSolvingSession} stays a single row per problem
 * and is kept separate from both {@link com.codefit.model.ProblemProgress} and
 * {@link com.codefit.model.ProblemAttempt} (#142).
 */
class ProblemSolvingSessionServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemSolvingSessionService sessionService = new ProblemSolvingSessionService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void startingASessionTwiceResumesTheSameRowInsteadOfCreatingATwoRows() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-SESSION-1",
                "Session Fixture", null, "General", null, List.of());

        ProblemSolvingSession first = sessionService.startOrResume(problem.getId());
        ProblemSolvingSession second = sessionService.startOrResume(problem.getId());

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void recordingElapsedTimeAccumulatesPerPhaseOnTheSamePersistentSession() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-SESSION-2",
                "Timer Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());

        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.READING, 90);
        ProblemSolvingSession afterThinking = sessionService.recordElapsedTime(problem.getId(), SolvingPhase.THINKING, 200);

        assertEquals(90, afterThinking.getReadingSecondsElapsed());
        assertEquals(200, afterThinking.getThinkingSecondsElapsed());
        assertEquals(SolvingPhase.THINKING, afterThinking.getPhase());
    }

    @Test
    void endingASessionKeepsItsAccumulatedTimeButMarksItInactive() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-SESSION-3",
                "End Session Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.CODING, 500);

        sessionService.endSession(problem.getId());

        ProblemSolvingSession ended = sessionService.findSession(problem.getId()).orElseThrow();
        assertFalse(ended.isActive());
        assertEquals(500, ended.getCodingSecondsElapsed());
    }

    @Test
    void resettingASessionClearsItCompletely() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-SESSION-4",
                "Reset Fixture", null, "General", null, List.of());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.DEBUGGING, 45);

        sessionService.reset(problem.getId());

        assertTrue(sessionService.findSession(problem.getId()).isEmpty());
    }

    @Test
    void pausingExcludesFurtherElapsedTimeUntilResumed() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-145-SESSION-5",
                "Pause Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.CODING, 30);

        ProblemSolvingSession paused = sessionService.pause(problem.getId());
        assertTrue(paused.isPaused());

        ProblemSolvingSession stillPaused = sessionService.recordElapsedTime(problem.getId(), SolvingPhase.CODING, 20);
        assertEquals(30, stillPaused.getCodingSecondsElapsed(), "paused time must never accumulate");

        ProblemSolvingSession resumed = sessionService.resume(problem.getId());
        assertFalse(resumed.isPaused());

        ProblemSolvingSession afterResume = sessionService.recordElapsedTime(problem.getId(), SolvingPhase.CODING, 20);
        assertEquals(50, afterResume.getCodingSecondsElapsed(), "elapsed time accumulates again once resumed");
    }

    @Test
    void onlyOnePhaseAccumulatesTimeAtATimeAndSwitchingNeverLosesAnEarlierPhasesTotal() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-145-SESSION-6",
                "Phase Switch Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());

        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.READING, 60);
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.THINKING, 0); // an accidental-switch correction
        ProblemSolvingSession afterSwitchBack = sessionService.recordElapsedTime(problem.getId(), SolvingPhase.READING, 15);

        assertEquals(75, afterSwitchBack.getReadingSecondsElapsed(), "switching away and back must not lose the earlier reading total");
        assertEquals(0, afterSwitchBack.getThinkingSecondsElapsed());
        assertEquals(SolvingPhase.READING, afterSwitchBack.getPhase());
    }

    @Test
    void startOrResumeUnpausesAnExistingSession() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-145-SESSION-7",
                "Unpause Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.READING, 10);
        sessionService.pause(problem.getId());

        ProblemSolvingSession resumed = sessionService.startOrResume(problem.getId());

        assertFalse(resumed.isPaused());
        assertTrue(resumed.isActive());
    }

    @Test
    void resumingAnEndedSessionReactivatesItWithoutLosingAccumulatedTime() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-145-SESSION-8",
                "Reactivate Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.CODING, 40);
        sessionService.endSession(problem.getId());
        assertFalse(sessionService.findSession(problem.getId()).orElseThrow().isActive());

        ProblemSolvingSession reactivated = sessionService.resume(problem.getId());

        assertTrue(reactivated.isActive());
        assertEquals(40, reactivated.getCodingSecondsElapsed());
    }

    @Test
    void aSessionSurvivesReloadingFromAFreshServiceInstanceSimulatingAnApplicationRestart() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-145-SESSION-9",
                "Restart Fixture", null, "General", null, List.of());
        sessionService.reset(problem.getId());
        sessionService.recordElapsedTime(problem.getId(), SolvingPhase.THINKING, 77);
        sessionService.pause(problem.getId());

        // A brand-new service (and repository) instance stands in for the app being restarted:
        // there is no in-memory state to carry over, only what was persisted.
        ProblemSolvingSessionService afterRestart = new ProblemSolvingSessionService();
        ProblemSolvingSession reloaded = afterRestart.findSession(problem.getId()).orElseThrow();

        assertEquals(77, reloaded.getThinkingSecondsElapsed());
        assertTrue(reloaded.isPaused(), "the session must resume in the same paused state it was left in");
    }
}
