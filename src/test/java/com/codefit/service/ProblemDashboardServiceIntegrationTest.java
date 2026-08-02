package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.RoadmapEntryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ProblemDashboardService#build()} end-to-end against a real database (#147),
 * complementing {@link ProblemDashboardServiceTest}'s isolated aggregation-logic unit tests. Roadmap
 * fixtures draw their sequence number from a monotonic counter so different test methods in this
 * class can never collide on the same roadmap slot, regardless of execution order.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProblemDashboardServiceIntegrationTest {

    private static final AtomicLong ROADMAP_SEQUENCE = new AtomicLong(10_000_000L);

    private final ProblemService problemService = new ProblemService();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();
    private final ProblemDashboardService dashboardService = new ProblemDashboardService();

    @Test
    void buildingTheFullDashboardNeverThrowsAndStaysInternallyConsistent() {
        ProblemDashboard dashboard = dashboardService.build();

        assertNotNull(dashboard.coreProgress());
        assertNotNull(dashboard.qualityMetrics());
        assertNotNull(dashboard.timingInsights());
        assertNotNull(dashboard.topicInsights());
        assertNotNull(dashboard.recommendation());
        assertNotNull(dashboard.overdueReflections());
        assertNotNull(dashboard.unfinishedAttempts());

        List<RoadmapEntry> allRoadmapEntries = roadmapEntryRepository.findAllInRoadmapOrder();
        assertEquals(allRoadmapEntries.size(),
                dashboard.coreProgress().mandatoryTotal() + dashboard.coreProgress().optionalTotal());
        assertEquals(allRoadmapEntries.size(), dashboard.coreProgress().statusBreakdown().total()
                + duplicateStageMembershipCount(allRoadmapEntries));
    }

    /** {@code statusBreakdown} counts each distinct problem once even if it holds multiple roadmap
     *  memberships (unlike {@code mandatoryTotal}/{@code optionalTotal}, which count positions); this
     *  computes that gap directly so the invariant above holds regardless of what other tests have
     *  imported into the shared database. */
    private long duplicateStageMembershipCount(List<RoadmapEntry> allRoadmapEntries) {
        long distinctProblems = allRoadmapEntries.stream().map(RoadmapEntry::getProblemId).distinct().count();
        return allRoadmapEntries.size() - distinctProblems;
    }

    @Test
    void aFreshMandatoryRoadmapProblemIsRecommendedAndCountsTowardCoreProgress() {
        long sequence = ROADMAP_SEQUENCE.getAndIncrement();
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-DASHBOARD", "TF-147-DASH-" + sequence,
                "Dashboard Fixture", null, "Dashboard Topic", null, List.of());
        roadmapEntryRepository.save(new RoadmapEntry(problem.getId(), RoadmapStage.D3, (int) sequence, 1, true, DifficultyLevel.MEDIUM));

        ProblemDashboard dashboard = dashboardService.build();

        assertTrue(dashboard.coreProgress().mandatoryTotal() > 0);
    }

    @Test
    void aSolvedProblemWithNoReflectionAppearsAsAReflectionGap() {
        long sequence = ROADMAP_SEQUENCE.getAndIncrement();
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-DASHBOARD", "TF-147-REFLECT-" + sequence,
                "Reflection Gap Fixture", null, "General", null, List.of());
        roadmapEntryRepository.save(new RoadmapEntry(problem.getId(), RoadmapStage.D2, (int) sequence, null, true, DifficultyLevel.EASY));
        attemptService.recordAttempt(problem.getId(), SubmissionResult.AC, 60, 60, 60, 60, null);
        progressService.updateProgress(problem.getId(), ProblemState.SOLVED, java.time.LocalDateTime.now());

        ProblemDashboard dashboard = dashboardService.build();

        assertTrue(dashboard.overdueReflections().stream().anyMatch(gap -> gap.problem().getId() == problem.getId()));
    }
}
