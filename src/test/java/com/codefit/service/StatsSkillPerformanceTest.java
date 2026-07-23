package com.codefit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsSkillPerformanceTest {

    @Test
    void accuracyPercentUsesObjectiveCorrectCountNotSelfRating() {
        // 4 reviews, only 1 objectively correct, but 3 were self-rated GOOD/EASY.
        StatsSkillPerformance performance = new StatsSkillPerformance("SQL", 5, 1, 4, 1, 0, 1, 2, 1);

        assertEquals(25.0, performance.accuracyPercent());
    }

    @Test
    void needsPracticeStillReflectsAgainHardScheduleRatings() {
        StatsSkillPerformance weak = new StatsSkillPerformance("SQL", 5, 1, 5, 5, 2, 1, 1, 1);
        assertTrue(weak.needsPractice());

        StatsSkillPerformance strong = new StatsSkillPerformance("SQL", 5, 1, 5, 5, 0, 0, 3, 2);
        assertFalse(strong.needsPractice());
    }

    @Test
    void emptyPerformanceHasZeroPercentagesNotDivideByZero() {
        StatsSkillPerformance empty = new StatsSkillPerformance("Empty", 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, empty.accuracyPercent());
        assertEquals(0.0, empty.needsPracticeRate());
        assertFalse(empty.needsPractice());
    }
}
