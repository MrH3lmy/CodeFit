package com.codefit.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemSolvingSessionTest {

    @Test
    void startProducesAFreshActiveSessionAtTheReadingPhaseWithNoElapsedTime() {
        ProblemSolvingSession session = ProblemSolvingSession.start(42L);

        assertEquals(42L, session.getProblemId());
        assertEquals(SolvingPhase.READING, session.getPhase());
        assertTrue(session.isActive());
        assertEquals(0, session.getReadingSecondsElapsed());
        assertEquals(0, session.getThinkingSecondsElapsed());
        assertEquals(0, session.getCodingSecondsElapsed());
        assertEquals(0, session.getDebuggingSecondsElapsed());
    }

    @Test
    void addElapsedSecondsAccumulatesOnlyTheGivenPhasesCounter() {
        ProblemSolvingSession session = ProblemSolvingSession.start(1L);

        session.addElapsedSeconds(SolvingPhase.READING, 30);
        session.addElapsedSeconds(SolvingPhase.READING, 15);
        session.addElapsedSeconds(SolvingPhase.CODING, 200);

        assertEquals(45, session.getReadingSecondsElapsed());
        assertEquals(200, session.getCodingSecondsElapsed());
        assertEquals(0, session.getThinkingSecondsElapsed());
        assertEquals(0, session.getDebuggingSecondsElapsed());
    }

    @Test
    void addElapsedSecondsIgnoresNonPositiveOrMissingPhase() {
        ProblemSolvingSession session = ProblemSolvingSession.start(1L);

        session.addElapsedSeconds(SolvingPhase.THINKING, 0);
        session.addElapsedSeconds(SolvingPhase.THINKING, -5);
        session.addElapsedSeconds(null, 10);

        assertEquals(0, session.getThinkingSecondsElapsed());
    }
}
