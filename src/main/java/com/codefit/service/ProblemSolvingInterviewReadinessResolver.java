package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the interview-specific {@code PROBLEM_SOLVING} requirement from actual fresh-attempt and
 * independence signal. This intentionally does not reuse the Problem Dashboard's headline
 * first-submission metric because that metric includes ACX for its own dashboard semantics; an
 * interview readiness score must not let ACX improve fresh live-coding readiness.
 */
class ProblemSolvingInterviewReadinessResolver implements InterviewRequirementReadinessResolver {
    static final String SUPPORTED_KEY = "problem-solving-training";

    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ProblemAttemptRepository attemptRepository;
    private final ProblemProgressRepository progressRepository;

    ProblemSolvingInterviewReadinessResolver() {
        this(new RoadmapEntryRepository(), new ProblemAttemptRepository(), new ProblemProgressRepository());
    }

    ProblemSolvingInterviewReadinessResolver(RoadmapEntryRepository roadmapEntryRepository,
                                             ProblemAttemptRepository attemptRepository,
                                             ProblemProgressRepository progressRepository) {
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.attemptRepository = attemptRepository;
        this.progressRepository = progressRepository;
    }

    @Override
    public boolean supports(InterviewMaterialType type) {
        return type == InterviewMaterialType.PROBLEM_SOLVING;
    }

    @Override
    public InterviewRequirementReadiness resolve(InterviewRequirement requirement) {
        String key = requirement.getReference().key();
        if (!SUPPORTED_KEY.equals(key)) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "Unsupported problem-solving material key '" + key + "'; only '" + SUPPORTED_KEY + "' is resolvable.");
        }

        InterviewProblemSolvingMetrics metrics = buildMetrics(
                roadmapEntryRepository.findAllInRoadmapOrder(),
                attemptRepository.findAll(),
                progressRepository.findAll());
        return fromMetrics(requirement, metrics);
    }

    /**
     * Interview-specific quality signal. A "fresh" first submission is a real first attempt whose
     * result is not ACX. Only a clean AC counts as fresh-first-submission success. Independence is
     * measured only for solved problems that also had such a fresh attempt and recorded a
     * {@link SolvedWith} reflection, keeping memorized/imported/ambiguous cases out of the interview
     * signal rather than guessing.
     */
    record InterviewProblemSolvingMetrics(
            int freshAttemptSampleCount,
            double freshFirstSubmissionAccuracyPercent,
            int independenceSampleCount,
            double independentSolveRatePercent,
            int mandatoryTotal,
            int mandatoryCompleted
    ) {
    }

    static InterviewProblemSolvingMetrics buildMetrics(List<RoadmapEntry> roadmapEntries,
                                                        List<ProblemAttempt> attempts,
                                                        List<ProblemProgress> progressRows) {
        Set<Long> scopedProblemIds = roadmapEntries.stream()
                .map(RoadmapEntry::getProblemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = attempts.stream()
                .collect(Collectors.groupingBy(ProblemAttempt::problemId));
        Map<Long, ProblemProgress> progressByProblemId = progressRows.stream()
                .collect(Collectors.toMap(ProblemProgress::getProblemId, Function.identity()));

        int freshSample = 0;
        int freshAccurate = 0;
        int independenceSample = 0;
        int independent = 0;

        for (Long problemId : scopedProblemIds) {
            ProblemAttempt firstAttempt = attemptsByProblemId.getOrDefault(problemId, List.of()).stream()
                    .filter(attempt -> attempt.attemptNumber() == 1)
                    .findFirst()
                    .orElse(null);
            if (firstAttempt == null || firstAttempt.submissionResult() == SubmissionResult.ACX) {
                continue;
            }

            freshSample++;
            if (firstAttempt.submissionResult() == SubmissionResult.AC) {
                freshAccurate++;
            }

            ProblemProgress progress = progressByProblemId.get(problemId);
            if (progress != null && progress.getState() == ProblemState.SOLVED && progress.getSolvedWith() != null) {
                independenceSample++;
                if (progress.getSolvedWith() == SolvedWith.SELF) {
                    independent++;
                }
            }
        }

        int mandatoryTotal = 0;
        int mandatoryCompleted = 0;
        for (RoadmapEntry entry : roadmapEntries) {
            if (!entry.isMandatory()) {
                continue;
            }
            mandatoryTotal++;
            ProblemProgress progress = progressByProblemId.get(entry.getProblemId());
            if (progress != null && progress.getState() == ProblemState.SOLVED) {
                mandatoryCompleted++;
            }
        }

        return new InterviewProblemSolvingMetrics(
                freshSample,
                freshSample == 0 ? 0.0 : freshAccurate * 100.0 / freshSample,
                independenceSample,
                independenceSample == 0 ? 0.0 : independent * 100.0 / independenceSample,
                mandatoryTotal,
                mandatoryCompleted);
    }

    static InterviewRequirementReadiness fromMetrics(InterviewRequirement requirement,
                                                      InterviewProblemSolvingMetrics metrics) {
        String roadmapContext = "Roadmap progress (context only, not part of the score): "
                + metrics.mandatoryCompleted() + "/" + metrics.mandatoryTotal() + " mandatory problems solved.";

        if (metrics.freshAttemptSampleCount() < ProblemDashboard.MIN_SAMPLE_SIZE) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "Only " + metrics.freshAttemptSampleCount() + " fresh first-attempt sample(s); need at least "
                            + ProblemDashboard.MIN_SAMPLE_SIZE + ". ACX does not count toward this interview signal. "
                            + roadmapContext);
        }
        if (metrics.independenceSampleCount() < ProblemDashboard.MIN_SAMPLE_SIZE) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "Fresh-attempt signal exists, but only " + metrics.independenceSampleCount()
                            + " solved problem(s) have a usable SELF/EDITORIAL independence reflection; need at least "
                            + ProblemDashboard.MIN_SAMPLE_SIZE + ". " + roadmapContext);
        }

        double score = (metrics.freshFirstSubmissionAccuracyPercent() + metrics.independentSolveRatePercent()) / 2.0;
        return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.PROBLEM_SOLVING, score,
                "Problem-solving interview signal: average of fresh first-submission accuracy ("
                        + Math.round(metrics.freshFirstSubmissionAccuracyPercent()) + "%, n="
                        + metrics.freshAttemptSampleCount() + ") and independent-solve rate ("
                        + Math.round(metrics.independentSolveRatePercent()) + "%, n="
                        + metrics.independenceSampleCount() + "). ACX is excluded from the fresh-attempt signal. "
                        + roadmapContext);
    }
}
