package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.RoadmapEntryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ProblemSolvingInterviewReadinessResolver} against a real database (#178 Slice 2),
 * complementing {@link ProblemSolvingInterviewReadinessResolverTest}'s database-free tests of
 * {@link ProblemSolvingInterviewReadinessResolver#fromCoreProgress}.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProblemSolvingInterviewReadinessResolverIntegrationTest {

    private final ProblemService problemService = new ProblemService();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemDashboardService problemDashboardService = new ProblemDashboardService();
    private final ProblemSolvingInterviewReadinessResolver resolver =
            new ProblemSolvingInterviewReadinessResolver(problemDashboardService);
    private final InterviewRequirement requirement = InterviewRequirement.available("problem-solving-system",
            "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING, "problem-solving-training");

    @Test
    void resolverReflectsRealMandatoryRoadmapCompletionRate() {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-INTERVIEW-READINESS",
                "TF-IR-PS-" + System.nanoTime(), "Interview Readiness Fixture", null, "General", null, List.of());
        roadmapEntryRepository.save(new RoadmapEntry(problem.getId(), RoadmapStage.D3, 5_000_001, 1, true, DifficultyLevel.MEDIUM));
        progressService.updateProgress(problem.getId(), ProblemState.SOLVED, LocalDateTime.now());

        ProblemDashboard.CoreProgress liveCoreProgress = problemDashboardService.build().coreProgress();
        assertTrue(liveCoreProgress.mandatoryTotal() > 0);

        InterviewRequirementReadiness readiness = resolver.resolve(requirement);

        assertTrue(readiness.measurable());
        int expectedScorePercent = (int) Math.round(
                liveCoreProgress.mandatoryCompleted() * 100.0 / liveCoreProgress.mandatoryTotal());
        assertEquals(expectedScorePercent, readiness.scorePercent(),
                "the resolver must report exactly the same mandatory-completion rate the dashboard itself computes");
        assertEquals(InterviewMaterialType.PROBLEM_SOLVING, readiness.sourceType());
    }
}
