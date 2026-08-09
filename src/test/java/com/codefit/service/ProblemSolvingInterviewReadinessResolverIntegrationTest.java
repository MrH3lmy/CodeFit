package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.RoadmapEntryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ProblemSolvingInterviewReadinessResolver} against a real database, complementing
 * {@link ProblemSolvingInterviewReadinessResolverTest}'s database-free signal tests.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProblemSolvingInterviewReadinessResolverIntegrationTest {

    private final ProblemService problemService = new ProblemService();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();
    private final ProblemDashboardService problemDashboardService = new ProblemDashboardService();
    private final ProblemSolvingInterviewReadinessResolver resolver = new ProblemSolvingInterviewReadinessResolver();
    private final InterviewRequirement requirement = InterviewRequirement.available("problem-solving-system",
            "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING,
            ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY);

    private Problem fixtureProblem(String suffix, int sequence) {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-INTERVIEW-READINESS",
                "TF-IR-PS-" + suffix + "-" + System.nanoTime(), "Interview Readiness Fixture " + suffix, null,
                "General", null, List.of());
        roadmapEntryRepository.save(new RoadmapEntry(problem.getId(), RoadmapStage.D3, sequence, 1, true, DifficultyLevel.MEDIUM));
        return problem;
    }

    @Test
    @Order(1)
    void aNonZeroRoadmapWithNoAttemptsStaysUnmeasurable() {
        ProblemDashboard.CoreProgress liveCoreProgress = problemDashboardService.build().coreProgress();
        assertTrue(liveCoreProgress.mandatoryTotal() > 0, "the pilot roadmap should already be seeded");

        InterviewRequirementReadiness readiness = resolver.resolve(requirement);

        assertFalse(readiness.measurable(), "a nonzero roadmap with zero real attempts must stay unmeasurable");
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    @Order(2)
    void resolverReflectsRealFreshAccuracyAndIndependenceSignal() {
        Problem accurateSelfSolvedA = fixtureProblem("accurate-self-a", 6_000_001);
        attemptService.recordAttempt(accurateSelfSolvedA.getId(), SubmissionResult.AC, 60, 60, 60, 60, null);
        progressService.updateProgress(accurateSelfSolvedA.getId(), ProblemState.SOLVED, LocalDateTime.now());
        progressService.updateReflection(accurateSelfSolvedA.getId(),
                new ProblemReflection(null, SolvedWith.SELF, null, null, null, null, null, null, null, null, false, false, false, false));

        Problem accurateSelfSolvedB = fixtureProblem("accurate-self-b", 6_000_002);
        attemptService.recordAttempt(accurateSelfSolvedB.getId(), SubmissionResult.AC, 60, 60, 60, 60, null);
        progressService.updateProgress(accurateSelfSolvedB.getId(), ProblemState.SOLVED, LocalDateTime.now());
        progressService.updateReflection(accurateSelfSolvedB.getId(),
                new ProblemReflection(null, SolvedWith.SELF, null, null, null, null, null, null, null, null, false, false, false, false));

        Problem missedThenEditorialSolvedC = fixtureProblem("editorial-c", 6_000_003);
        attemptService.recordAttempt(missedThenEditorialSolvedC.getId(), SubmissionResult.WA, 60, 60, 60, 60, null);
        attemptService.recordAttempt(missedThenEditorialSolvedC.getId(), SubmissionResult.AC, 60, 60, 60, 60, null);
        progressService.updateProgress(missedThenEditorialSolvedC.getId(), ProblemState.SOLVED, LocalDateTime.now());
        progressService.updateReflection(missedThenEditorialSolvedC.getId(),
                new ProblemReflection(null, SolvedWith.EDITORIAL, null, null, null, null, null, null, null, null, false, false, false, false));

        InterviewRequirementReadiness readiness = resolver.resolve(requirement);

        assertTrue(readiness.measurable());
        assertEquals(67, readiness.scorePercent(),
                "2/3 clean first-attempt AC and 2/3 independent solves should average to 66.67%, rounded to 67");
        assertEquals(InterviewMaterialType.PROBLEM_SOLVING, readiness.sourceType());
    }
}
