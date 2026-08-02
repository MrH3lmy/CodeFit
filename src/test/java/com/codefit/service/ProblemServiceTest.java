package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the uniqueness and roadmap-membership invariants {@link ProblemService} is responsible
 * for (#142), using fixed TEST-FIXTURE identifiers safe to re-insert across repeated runs.
 *
 * <p>Roadmap slot numbers come from a monotonic counter rather than small fixed literals:
 * {@code addToRoadmap}/{@code upsertRoadmapMembership}'s reposition-not-duplicate and slot-conflict
 * behavior doesn't care what the actual numbers are, and this guarantees every test method in this
 * class gets its own disjoint block of slots within this class's isolated database (#175), regardless
 * of execution order, without depending on randomness to avoid a collision.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProblemServiceTest {

    private static final AtomicInteger ROADMAP_ORDER_SEQUENCE = new AtomicInteger(1);

    private final ProblemService problemService = new ProblemService();
    private int nextOrder = ROADMAP_ORDER_SEQUENCE.getAndAdd(1_000);

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

        problemService.addToRoadmap(problem.getId(), RoadmapStage.A, nextOrder++, 1, true, DifficultyLevel.EASY);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.C1, nextOrder++, 2, false, DifficultyLevel.MEDIUM);

        List<RoadmapEntry> entries = problemService.getRoadmapEntriesForProblem(problem.getId());
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.A));
        assertTrue(entries.stream().anyMatch(entry -> entry.getStage() == RoadmapStage.C1));
    }

    @Test
    void reAddingTheSameProblemToTheSameStageUpdatesThePositionInsteadOfDuplicatingMembership() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-4",
                "Repositioned Problem", null, "General", null, List.of());

        int firstSlot = nextOrder++;
        int repositionedSlot = nextOrder++;
        problemService.addToRoadmap(problem.getId(), RoadmapStage.B, firstSlot, 1, true, DifficultyLevel.EASY);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.B, repositionedSlot, 2, false, DifficultyLevel.HARD);

        List<RoadmapEntry> entries = problemService.getRoadmapEntriesForProblem(problem.getId());
        long stageBCount = entries.stream().filter(entry -> entry.getStage() == RoadmapStage.B).count();
        assertEquals(1, stageBCount, "re-registering in the same stage must update, not duplicate, the membership");

        RoadmapEntry entry = entries.stream().filter(candidate -> candidate.getStage() == RoadmapStage.B).findFirst().orElseThrow();
        assertEquals(repositionedSlot, entry.getSequenceOrder());
        assertEquals(DifficultyLevel.HARD, entry.getSuggestedLevel());
    }

    @Test
    void twoDifferentProblemsCannotBeAssignedTheSameRoadmapSlot() {
        Problem first = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-5",
                "Slot Owner", null, "General", null, List.of());
        Problem second = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-6",
                "Slot Challenger", null, "General", null, List.of());

        int contestedSlot = nextOrder++;
        problemService.addToRoadmap(first.getId(), RoadmapStage.D2, contestedSlot, null, true, null);

        assertThrows(IllegalStateException.class,
                () -> problemService.addToRoadmap(second.getId(), RoadmapStage.D2, contestedSlot, null, true, null));
    }

    @Test
    void repositioningAnExistingMembershipIntoASlotAnotherProblemAlreadyHoldsIsAConflictNotASilentOverwrite() {
        Problem first = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-7",
                "Slot Owner", null, "General", null, List.of());
        Problem second = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-8",
                "Repositioning Challenger", null, "General", null, List.of());

        int occupiedSlot = nextOrder++;
        int secondsOwnSlot = nextOrder++;
        problemService.addToRoadmap(first.getId(), RoadmapStage.C1, occupiedSlot, null, true, null);
        problemService.addToRoadmap(second.getId(), RoadmapStage.C1, secondsOwnSlot, null, true, null);

        // Re-registering an *existing* membership (not a brand-new one) into a slot a different
        // problem already holds must be reported as a conflict - the same as if it were new - rather
        // than either silently moving the first problem out, or hitting the raw UNIQUE(stage,
        // sequence_order) SQL constraint and rolling back an entire import with a generic error.
        assertThrows(IllegalStateException.class,
                () -> problemService.addToRoadmap(second.getId(), RoadmapStage.C1, occupiedSlot, null, true, null));

        RoadmapEntry firstsEntry = problemService.getRoadmapEntriesForProblem(first.getId()).stream()
                .filter(entry -> entry.getStage() == RoadmapStage.C1).findFirst().orElseThrow();
        assertEquals(occupiedSlot, firstsEntry.getSequenceOrder(), "the rejected reposition must never have moved the original occupant out");
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
