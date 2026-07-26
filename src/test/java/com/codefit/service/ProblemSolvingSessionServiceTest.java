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
}
