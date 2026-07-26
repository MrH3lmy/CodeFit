package com.codefit.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProblemProgressTest {

    @Test
    void notStartedProducesABlankRecordInTheNotStartedState() {
        ProblemProgress progress = ProblemProgress.notStarted(7L);

        assertEquals(7L, progress.getProblemId());
        assertEquals(ProblemState.NOT_STARTED, progress.getState());
        assertNull(progress.getPerceivedDifficulty());
        assertNull(progress.getSolvedWith());
        assertNull(progress.getFinalCategory());
        assertNull(progress.getCompletedAt());
    }

    @Test
    void setStateNeverAllowsANullState() {
        ProblemProgress progress = ProblemProgress.notStarted(1L);

        progress.setState(null);

        assertEquals(ProblemState.NOT_STARTED, progress.getState());
    }
}
