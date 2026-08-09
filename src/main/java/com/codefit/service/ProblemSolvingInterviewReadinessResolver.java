package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;

/**
 * Resolves a {@code PROBLEM_SOLVING} requirement using the mandatory-roadmap completion rate already
 * computed as {@link ProblemDashboard.CoreProgress} - the same headline "how much of the roadmap is
 * solved" figure shown on the Problem-Solving Dashboard - rather than a new algorithm blending
 * accuracy, timing, and independence signals that Slice 2 has no product requirement for. Solved
 * rate over mandatory roadmap problems is the simplest existing metric that answers "is this person
 * coding-ready", and {@link ProblemDashboardService} already treats zero solved out of a nonzero
 * roadmap as a real 0%, so this resolver does the same - only an empty roadmap (nothing imported
 * yet) is reported unmeasured rather than 0%.
 */
class ProblemSolvingInterviewReadinessResolver implements InterviewRequirementReadinessResolver {
    private final ProblemDashboardService problemDashboardService;

    ProblemSolvingInterviewReadinessResolver() {
        this(new ProblemDashboardService());
    }

    ProblemSolvingInterviewReadinessResolver(ProblemDashboardService problemDashboardService) {
        this.problemDashboardService = problemDashboardService;
    }

    @Override
    public boolean supports(InterviewMaterialType type) {
        return type == InterviewMaterialType.PROBLEM_SOLVING;
    }

    @Override
    public InterviewRequirementReadiness resolve(InterviewRequirement requirement) {
        return fromCoreProgress(requirement, problemDashboardService.build().coreProgress());
    }

    /** Pure aggregation over an already-loaded {@link ProblemDashboard.CoreProgress}, independent of
     *  the database so this specific branch (an empty roadmap) is directly unit testable even though
     *  {@code DatabaseConfig} seeds a small pilot roadmap into every real database today. */
    static InterviewRequirementReadiness fromCoreProgress(InterviewRequirement requirement, ProblemDashboard.CoreProgress coreProgress) {
        if (coreProgress.mandatoryTotal() == 0) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "No problem-solving roadmap has been imported yet.");
        }

        double solvedRatePercent = coreProgress.mandatoryCompleted() * 100.0 / coreProgress.mandatoryTotal();
        return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.PROBLEM_SOLVING, solvedRatePercent,
                "Mandatory roadmap completion: " + coreProgress.mandatoryCompleted() + "/" + coreProgress.mandatoryTotal()
                        + " problems solved.");
    }
}
