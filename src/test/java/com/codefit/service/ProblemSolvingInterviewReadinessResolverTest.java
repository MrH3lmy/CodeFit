package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ProblemSolvingInterviewReadinessResolver#fromQualityMetrics}, the pure aggregation
 * this resolver delegates to, entirely against hand-built {@link ProblemDashboard.QualityMetrics}/
 * {@link ProblemDashboard.CoreProgress} fixtures - independent of the database, since
 * {@code DatabaseConfig} seeds a small pilot roadmap (#171) into every real database, so an
 * empty-roadmap scenario isn't reachable end-to-end there. The real-database integration path lives
 * in {@link ProblemSolvingInterviewReadinessResolverIntegrationTest}.
 */
class ProblemSolvingInterviewReadinessResolverTest {

    private final InterviewRequirement requirement = InterviewRequirement.available("problem-solving-system",
            "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING,
            ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY);

    private ProblemDashboard.CoreProgress coreProgress(int mandatoryTotal, int mandatoryCompleted) {
        return new ProblemDashboard.CoreProgress(null, null, false, mandatoryTotal, mandatoryCompleted, 0, 0,
                List.of(), new ProblemDashboard.StatusBreakdown(0, 0, 0, 0, 0), List.of());
    }

    private ProblemDashboard.QualityMetrics qualityMetrics(int firstSubmissionSampleCount, double firstSubmissionAccuracyPercent,
                                                            int independenceSampleCount, double independentSolveRatePercent,
                                                            double editorialDependencyRatePercent) {
        return new ProblemDashboard.QualityMetrics(firstSubmissionSampleCount, firstSubmissionAccuracyPercent,
                independenceSampleCount, independentSolveRatePercent, editorialDependencyRatePercent,
                0, 0.0, 0, 0.0, 0, 0, 0, 0);
    }

    // ==================================================================================
    // Measurability gate: ProblemDashboard.MIN_SAMPLE_SIZE (3) real first-submission attempts
    // ==================================================================================

    @Test
    void noAttemptsAtAllIsUnmeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(0, 0.0, 0, 0.0, 0.0), coreProgress(10, 0));

        assertFalse(readiness.measurable());
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void fewerThanMinSampleSizeAttemptsIsUnmeasurable() {
        assertTrue(ProblemDashboard.MIN_SAMPLE_SIZE > 2, "test assumes MIN_SAMPLE_SIZE is at least 3");
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(ProblemDashboard.MIN_SAMPLE_SIZE - 1, 100.0, 0, 0.0, 0.0), coreProgress(10, 2));

        assertFalse(readiness.measurable(), "one below the sample-size bar must still be unmeasurable, not a fabricated score");
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void seededRoadmapAloneNeverCreatesAMeasurableScoreWithoutRealAttempts() {
        // Ten pilot problems exist (DatabaseConfig #171) but nothing has been attempted yet.
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(0, 0.0, 0, 0.0, 0.0), coreProgress(10, 0));

        assertFalse(readiness.measurable(),
                "a nonzero mandatory roadmap total must never by itself make this requirement measurable");
    }

    @Test
    void meetingTheSampleSizeBarMakesTheRequirementMeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(ProblemDashboard.MIN_SAMPLE_SIZE, 80.0, 0, 0.0, 0.0), coreProgress(10, 3));

        assertTrue(readiness.measurable());
        assertEquals(80, readiness.scorePercent(), "with no independence signal yet, score is first-submission accuracy alone");
    }

    // ==================================================================================
    // Score formula
    // ==================================================================================

    @Test
    void assistedEditorialSolvingScoresLowerThanEquallyAccurateIndependentSolving() {
        InterviewRequirementReadiness independentSolver = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(5, 80.0, 5, 90.0, 10.0), coreProgress(10, 5));
        InterviewRequirementReadiness editorialHeavySolver = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(5, 80.0, 5, 10.0, 90.0), coreProgress(10, 5));

        assertEquals(85, independentSolver.scorePercent(), "(80 + 90) / 2 = 85");
        assertEquals(45, editorialHeavySolver.scorePercent(), "(80 + 10) / 2 = 45");
        assertTrue(editorialHeavySolver.scorePercent() < independentSolver.scorePercent(),
                "identical first-submission accuracy must not look equivalent once independent-solve rate differs this much");
    }

    @Test
    void poorQualitySignalProducesALowScore() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(5, 20.0, 5, 10.0, 90.0), coreProgress(10, 1));

        assertTrue(readiness.measurable());
        assertEquals(15, readiness.scorePercent(), "(20 + 10) / 2 = 15");
    }

    @Test
    void strongQualitySignalProducesAHighScore() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(8, 90.0, 8, 95.0, 5.0), coreProgress(10, 8));

        assertTrue(readiness.measurable());
        assertEquals(93, readiness.scorePercent(), "(90 + 95) / 2 = 92.5, rounds to 93");
    }

    @Test
    void roadmapCompletionIsQuotedOnlyAsContextNeverPartOfTheScore() {
        InterviewRequirementReadiness lowCompletion = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(5, 70.0, 0, 0.0, 0.0), coreProgress(100, 1));
        InterviewRequirementReadiness highCompletion = ProblemSolvingInterviewReadinessResolver.fromQualityMetrics(
                requirement, qualityMetrics(5, 70.0, 0, 0.0, 0.0), coreProgress(100, 99));

        assertEquals(lowCompletion.scorePercent(), highCompletion.scorePercent(),
                "identical quality signal must score identically regardless of how much of the roadmap is completed");
        assertTrue(highCompletion.note().contains("99/100"), "completion is still surfaced as context in the note");
    }

    // ==================================================================================
    // Unsupported material key (Slice 2 review finding #3)
    // ==================================================================================

    @Test
    void anUnrecognizedProblemSolvingKeyIsUnmeasurableRatherThanSilentlyResolved() {
        InterviewRequirement wrongKeyRequirement = InterviewRequirement.available("problem-solving-system",
                "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING, "some-other-subsystem");
        ProblemSolvingInterviewReadinessResolver resolver = new ProblemSolvingInterviewReadinessResolver(new ProblemDashboardService());

        InterviewRequirementReadiness readiness = resolver.resolve(wrongKeyRequirement);

        assertFalse(readiness.measurable(), "an unrecognized key must never silently fall through to the standard dashboard");
        assertEquals(null, readiness.scorePercent());
        assertTrue(readiness.note().contains("some-other-subsystem"));
    }

    @Test
    void theSupportedKeyMatchesWhatTheRevolutProfileActuallyDeclares() {
        assertEquals("problem-solving-training", ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY);
    }
}
