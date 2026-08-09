package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemSolvingInterviewReadinessResolverTest {

    private final InterviewRequirement requirement = InterviewRequirement.available("problem-solving-system",
            "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING,
            ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY);

    private ProblemSolvingInterviewReadinessResolver.InterviewProblemSolvingMetrics metrics(
            int freshSamples, double freshAccuracy, int independenceSamples, double independenceRate,
            int mandatoryTotal, int mandatoryCompleted) {
        return new ProblemSolvingInterviewReadinessResolver.InterviewProblemSolvingMetrics(
                freshSamples, freshAccuracy, independenceSamples, independenceRate, mandatoryTotal, mandatoryCompleted);
    }

    @Test
    void noFreshAttemptsIsUnmeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(0, 0.0, 0, 0.0, 10, 0));

        assertFalse(readiness.measurable());
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void fewerThanMinimumFreshAttemptsIsUnmeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(ProblemDashboard.MIN_SAMPLE_SIZE - 1, 100.0,
                        ProblemDashboard.MIN_SAMPLE_SIZE, 100.0, 10, 2));

        assertFalse(readiness.measurable());
    }

    @Test
    void enoughFreshAttemptsButInsufficientIndependenceDataIsStillUnmeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(ProblemDashboard.MIN_SAMPLE_SIZE, 100.0,
                        ProblemDashboard.MIN_SAMPLE_SIZE - 1, 100.0, 10, 3));

        assertFalse(readiness.measurable(),
                "live-coding readiness must not fall back to accuracy alone when independence is unknown");
    }

    @Test
    void bothSignalsAtMinimumSampleSizeMakeRequirementMeasurable() {
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(ProblemDashboard.MIN_SAMPLE_SIZE, 80.0,
                        ProblemDashboard.MIN_SAMPLE_SIZE, 60.0, 10, 3));

        assertTrue(readiness.measurable());
        assertEquals(70, readiness.scorePercent());
    }

    @Test
    void editorialHeavySolvingScoresLowerThanIndependentSolving() {
        InterviewRequirementReadiness independent = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(5, 80.0, 5, 90.0, 10, 5));
        InterviewRequirementReadiness editorialHeavy = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(5, 80.0, 5, 10.0, 10, 5));

        assertEquals(85, independent.scorePercent());
        assertEquals(45, editorialHeavy.scorePercent());
        assertTrue(editorialHeavy.scorePercent() < independent.scorePercent());
    }

    @Test
    void roadmapCompletionDoesNotChangeTheScore() {
        InterviewRequirementReadiness lowCompletion = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(5, 70.0, 5, 70.0, 100, 1));
        InterviewRequirementReadiness highCompletion = ProblemSolvingInterviewReadinessResolver.fromMetrics(
                requirement, metrics(5, 70.0, 5, 70.0, 100, 99));

        assertEquals(lowCompletion.scorePercent(), highCompletion.scorePercent());
        assertTrue(highCompletion.note().contains("99/100"));
    }

    @Test
    void acxAttemptsDoNotCountAsFreshSamplesOrIndependenceSamples() {
        List<RoadmapEntry> roadmap = new ArrayList<>();
        List<ProblemAttempt> attempts = new ArrayList<>();
        List<ProblemProgress> progressRows = new ArrayList<>();

        for (long problemId = 1; problemId <= 3; problemId++) {
            roadmap.add(new RoadmapEntry(problemId, RoadmapStage.A, (int) problemId, 1, true, null));
            attempts.add(new ProblemAttempt(0, problemId, 1, SubmissionResult.ACX,
                    null, null, null, null, LocalDateTime.now(), null, null));
            ProblemProgress progress = ProblemProgress.notStarted(problemId);
            progress.setState(ProblemState.SOLVED);
            progress.setSolvedWith(SolvedWith.SELF);
            progressRows.add(progress);
        }

        var signal = ProblemSolvingInterviewReadinessResolver.buildMetrics(roadmap, attempts, progressRows);

        assertEquals(0, signal.freshAttemptSampleCount(), "ACX must not satisfy the fresh-attempt sample bar");
        assertEquals(0, signal.independenceSampleCount(),
                "an ACX problem must not improve the interview-specific independence signal either");
        assertFalse(ProblemSolvingInterviewReadinessResolver.fromMetrics(requirement, signal).measurable());
    }

    @Test
    void cleanAcAttemptsAndRecordedIndependenceProduceExpectedSignal() {
        List<RoadmapEntry> roadmap = new ArrayList<>();
        List<ProblemAttempt> attempts = new ArrayList<>();
        List<ProblemProgress> progressRows = new ArrayList<>();

        for (long problemId = 1; problemId <= 3; problemId++) {
            roadmap.add(new RoadmapEntry(problemId, RoadmapStage.A, (int) problemId, 1, true, null));
            attempts.add(new ProblemAttempt(0, problemId, 1,
                    problemId <= 2 ? SubmissionResult.AC : SubmissionResult.WA,
                    null, null, null, null, LocalDateTime.now(), null, null));
            ProblemProgress progress = ProblemProgress.notStarted(problemId);
            progress.setState(ProblemState.SOLVED);
            progress.setSolvedWith(problemId <= 2 ? SolvedWith.SELF : SolvedWith.EDITORIAL);
            progressRows.add(progress);
        }

        var signal = ProblemSolvingInterviewReadinessResolver.buildMetrics(roadmap, attempts, progressRows);
        InterviewRequirementReadiness readiness = ProblemSolvingInterviewReadinessResolver.fromMetrics(requirement, signal);

        assertEquals(3, signal.freshAttemptSampleCount());
        assertEquals(3, signal.independenceSampleCount());
        assertTrue(readiness.measurable());
        assertEquals(67, readiness.scorePercent(), "66.67% fresh accuracy and 66.67% independence average to 66.67%");
    }

    @Test
    void anUnrecognizedProblemSolvingKeyIsUnmeasurableRatherThanSilentlyResolved() {
        InterviewRequirement wrongKeyRequirement = InterviewRequirement.available("problem-solving-system",
                "Problem-Solving Training", "description", InterviewMaterialType.PROBLEM_SOLVING, "some-other-subsystem");

        InterviewRequirementReadiness readiness = new ProblemSolvingInterviewReadinessResolver().resolve(wrongKeyRequirement);

        assertFalse(readiness.measurable());
        assertEquals(null, readiness.scorePercent());
        assertTrue(readiness.note().contains("some-other-subsystem"));
    }

    @Test
    void theSupportedKeyMatchesWhatTheRevolutProfileActuallyDeclares() {
        assertEquals("problem-solving-training", ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY);
    }
}
