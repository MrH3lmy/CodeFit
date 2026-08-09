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
 * Exercises {@link ProblemSolvingInterviewReadinessResolver} against a real database (#178 Slice 2
 * review fix), complementing {@link ProblemSolvingInterviewReadinessResolverTest}'s database-free
 * tests of {@link ProblemSolvingInterviewReadinessResolver#fromQualityMetrics}.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}. Method
 * order matters: the "no real attempts yet" assertion must run before the test that seeds real
 * attempts into this class's shared isolated database, hence the explicit {@link Order}.
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
    private final ProblemSolvingInterviewReadinessResolver resolver =
            new ProblemSolvingInterviewReadinessResolver(problemDashboardService);
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

    /**
     * The pilot roadmap (#171) seeds ten real, attemptable problems into every database, but none of
     * them have been attempted yet at this point in the class. This must not by itself make the
     * requirement measurable, and must never surface as a fabricated failing score.
     */
    @Test
    @Order(1)
    void aNonZeroRoadmapWithNoAttemptsStaysUnmeasurable() {
        ProblemDashboard.CoreProgress liveCoreProgress = problemDashboardService.build().coreProgress();
        assertTrue(liveCoreProgress.mandatoryTotal() > 0, "the pilot roadmap should already be seeded");

        InterviewRequirementReadiness readiness = resolver.resolve(requirement);

        assertFalse(readiness.measurable(), "a nonzero roadmap with zero real attempts must stay unmeasurable");
        assertEquals(null, readiness.scorePercent());
    }

    /**
     * Seeds three problems with real attempts (meeting {@code ProblemDashboard.MIN_SAMPLE_SIZE}) mixing
     * accurate/inaccurate first submissions and independent/editorial-assisted solves, then asserts the
     * resolver's score matches exactly what a fresh, independent call to
     * {@link ProblemDashboardService#build()} computes for the same data - proving the resolver is
     * wired to real quality signal, not a hardcoded or roadmap-derived number.
     */
    @Test
    @Order(2)
    void resolverReflectsRealFirstSubmissionAccuracyAndIndependenceSignal() {
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

        ProblemDashboard.QualityMetrics liveQualityMetrics = problemDashboardService.build().qualityMetrics();
        assertTrue(liveQualityMetrics.hasFirstSubmissionSignal(), "three real attempts should clear MIN_SAMPLE_SIZE");
        assertTrue(liveQualityMetrics.hasIndependenceSignal(), "three solved-with-known-source problems should clear MIN_SAMPLE_SIZE");

        InterviewRequirementReadiness readiness = resolver.resolve(requirement);

        assertTrue(readiness.measurable());
        int expectedScorePercent = (int) Math.round(
                (liveQualityMetrics.firstSubmissionAccuracyPercent() + liveQualityMetrics.independentSolveRatePercent()) / 2.0);
        assertEquals(expectedScorePercent, readiness.scorePercent(),
                "the resolver must report exactly the same quality-signal average the dashboard itself computes");
        assertEquals(InterviewMaterialType.PROBLEM_SOLVING, readiness.sourceType());
    }
}
