package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ProblemSolvingInterviewReadinessResolver#fromCoreProgress}, the pure aggregation
 * this resolver delegates to, entirely against hand-built {@link ProblemDashboard.CoreProgress}
 * fixtures - independent of the database, since {@code DatabaseConfig} seeds a small pilot roadmap
 * (#171) into every real database, so an empty-roadmap scenario isn't reachable end-to-end there.
 * The real-database integration path lives in
 * {@link ProblemSolvingInterviewReadinessResolverIntegrationTest}.
 */
class ProblemSolvingInterviewReadinessResolverTest {

    private final InterviewRequirement requirement = InterviewRequirement.available("problem-solving-system",
            "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING, "problem-solving-training");

    private ProblemDashboard.CoreProgress coreProgress(int mandatoryTotal, int mandatoryCompleted) {
        return new ProblemDashboard.CoreProgress(null, null, false, mandatoryTotal, mandatoryCompleted, 0, 0,
                List.of(), new ProblemDashboard.StatusBreakdown(0, 0, 0, 0, 0), List.of());
    }

    @Test
    void noRoadmapImportedYetIsUnmeasurableNotZero() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromCoreProgress(
                requirement, coreProgress(0, 0));

        assertFalse(readiness.measurable(), "an empty roadmap must not be reported as 0% readiness");
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void zeroSolvedOutOfANonZeroRoadmapIsARealZeroPercentNotUnmeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromCoreProgress(
                requirement, coreProgress(10, 0));

        assertTrue(readiness.measurable(), "a real roadmap with nothing solved yet is a genuine 0%, not missing data");
        assertEquals(0, readiness.scorePercent());
    }

    @Test
    void halfOfTheMandatoryRoadmapSolvedIsFiftyPercent() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromCoreProgress(
                requirement, coreProgress(4, 2));

        assertEquals(50, readiness.scorePercent());
    }
}
