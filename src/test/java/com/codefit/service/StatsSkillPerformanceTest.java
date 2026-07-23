package com.codefit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsSkillPerformanceTest {

    @Test
    void accuracyPercentUsesObjectiveCorrectCountNotSelfRating() {
        // 4 reviews, only 1 objectively correct, but 3 were self-rated GOOD/EASY.
        StatsSkillPerformance performance = new StatsSkillPerformance("SQL", 5, 1, 4, 4, 1, 0, 1, 2, 1);

        assertEquals(25.0, performance.accuracyPercent());
    }

    @Test
    void accuracyPercentExcludesSubjectiveReviewsFromDenominator() {
        // 5 total reviews, but only 2 were objectively graded (1 correct); 3 subjective reviews
        // must not dilute or otherwise affect the objective accuracy percentage.
        StatsSkillPerformance performance = new StatsSkillPerformance("Concepts", 5, 1, 5, 2, 1, 0, 0, 4, 1);

        assertEquals(50.0, performance.accuracyPercent());
    }

    @Test
    void needsPracticeStillReflectsAgainHardScheduleRatings() {
        StatsSkillPerformance weak = new StatsSkillPerformance("SQL", 5, 1, 5, 5, 5, 2, 1, 1, 1);
        assertTrue(weak.needsPractice());

        StatsSkillPerformance strong = new StatsSkillPerformance("SQL", 5, 1, 5, 5, 5, 0, 0, 3, 2);
        assertFalse(strong.needsPractice());
    }

    @Test
    void emptyPerformanceHasZeroPercentagesNotDivideByZero() {
        StatsSkillPerformance empty = new StatsSkillPerformance("Empty", 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, empty.accuracyPercent());
        assertEquals(0.0, empty.needsPracticeRate());
        assertFalse(empty.needsPractice());
    }

    @Test
    void allSubjectiveReviewsGiveZeroObjectiveAccuracyNotDivideByZeroCrash() {
        StatsSkillPerformance allSubjective = new StatsSkillPerformance("Concepts", 3, 0, 3, 0, 0, 0, 0, 2, 1);
        assertEquals(0.0, allSubjective.accuracyPercent());
    }
}
