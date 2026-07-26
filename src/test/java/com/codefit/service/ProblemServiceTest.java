package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Touches the local sqlite database the same way {@code AssessmentIsolationTest} does (idempotently,
 * using fixed TEST-FIXTURE identifiers safe to re-insert across repeated runs), verifying the
 * uniqueness and roadmap-membership invariants {@link ProblemService} is responsible for (#142).
 */
class ProblemServiceTest {

    private final ProblemService problemService = new ProblemService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void findOrCreateProblemNeverDuplicatesTheSamePlatformAndExternalCode() {
        Problem first = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-1",
                "Two Sum", "https://example.test/1", "Arrays", 4, List.of("https://example.test/1/editorial"));
        Problem second = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-1",
                "Two Sum (renamed)", "https://example.test/1-updated", "Hash Table", 5,
                List.of("https://example.test/1/editorial", "https://example.test/1/video"));

        assertEquals(first.getId(), second.getId(), "the same platform+externalCode must resolve to one Problem row");
        assertEquals("Two Sum (renamed)", second.getTitle());
        assertEquals("Hash Table", second.getTopic());
        assertEquals(5, second.getQualityRating());
    }

    @Test
    void sameExternalCodeOnDifferentPlatformsAreDistinctProblems() {
        Problem leetCode = problemService.findOrCreateProblem("TEST-FIXTURE-LEETCODE", "TF-142-2",
                "Sample Problem", null, "General", null, List.of());
        Problem codeforces = problemService.findOrCreateProblem("TEST-FIXTURE-CODEFORCES", "TF-142-2",
                "Sample Problem", null, "General", null, List.of());

        assertTrue(leetCode.getId() != codeforces.getId());
    }

    @Test
    void aProblemCanBeAddedToMultipleRoadmapStagesWithoutDuplicatingItsIdentity() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-3",
                "Repeated Across Stages", null, "General", null, List.of());

        problemService.addToRoadmap(problem.getId(), RoadmapStage.A, 1, 1, true, DifficultyLevel.EASY);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.C1, 3, 2, false, DifficultyLevel.MEDIUM);

        List<RoadmapEntry> entries = problemService.getRoadmapEntriesForProblem(problem.getId());
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.A));
        assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.C1));
    }

    @Test
    void reAddingTheSameProblemToTheSameStageUpdatesThePositionInsteadOfDuplicatingMembership() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-4",
                "Repositioned Problem", null, "General", null, List.of());

        problemService.addToRoadmap(problem.getId(), RoadmapStage.B, 5, 1, true, DifficultyLevel.EASY);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.B, 9, 2, false, DifficultyLevel.HARD);

        List<RoadmapEntry> entries = problemService.getRoadmapEntriesForProblem(problem.getId());
        long stageBCount = entries.stream().filter(entry -> entry.getStage() == RoadmapStage.B).count();
        assertEquals(1, stageBCount, "re-registering in the same stage must update, not duplicate, the membership");

        RoadmapEntry entry = entries.stream().filter(candidate -> candidate.getStage() == RoadmapStage.B).findFirst().orElseThrow();
        assertEquals(9, entry.getSequenceOrder());
        assertEquals(DifficultyLevel.HARD, entry.getSuggestedLevel());
    }

    @Test
    void twoDifferentProblemsCannotBeAssignedTheSameRoadmapSlot() {
        Problem first = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-5",
                "Slot Owner", null, "General", null, List.of());
        Problem second = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-6",
                "Slot Challenger", null, "General", null, List.of());

        problemService.addToRoadmap(first.getId(), RoadmapStage.D2, 42, null, true, null);

        assertThrows(IllegalStateException.class,
                () -> problemService.addToRoadmap(second.getId(), RoadmapStage.D2, 42, null, true, null));
    }

    @Test
    void roadmapStageOrdinalOrderMatchesTheBlindLearningOrder() {
        RoadmapStage[] stages = RoadmapStage.values();
        assertEquals(RoadmapStage.A, stages[0]);
        assertEquals(RoadmapStage.B, stages[1]);
        assertEquals(RoadmapStage.C1, stages[2]);
        assertEquals(RoadmapStage.C2, stages[3]);
        assertEquals(RoadmapStage.D1, stages[4]);
        assertEquals(RoadmapStage.D2, stages[5]);
        assertEquals(RoadmapStage.D3, stages[6]);
    }
}
