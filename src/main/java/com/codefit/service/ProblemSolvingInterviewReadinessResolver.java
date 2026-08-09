package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;

/**
 * Resolves a {@code PROBLEM_SOLVING} requirement from actual problem-solving <em>quality</em> signal
 * - not from how much of the roadmap has been imported or completed.
 *
 * <h2>Why not roadmap completion</h2>
 * {@code ProblemDashboard.CoreProgress}'s mandatory-roadmap completion rate measures progress through
 * material, not current interview performance: {@code DatabaseConfig} seeds a small pilot roadmap
 * (#171) into every database, so a completion-based score would report a fresh user with zero real
 * attempts as a measurable (and failing) 0%, purely because problems exist to be solved. That is
 * exactly the fabricated-critical-gate-failure this resolver must not produce.
 *
 * <h2>What is measured instead</h2>
 * {@code ProblemDashboard.QualityMetrics} already tracks first-submission accuracy and independent
 * (non-editorial-assisted) solving, gated by {@code ProblemDashboard.MIN_SAMPLE_SIZE} so a single
 * attempt can never look like a trend - see {@link #fromQualityMetrics} for the exact formula this
 * resolver reuses rather than duplicates.
 *
 * <h2>Roadmap completion's remaining role</h2>
 * Mandatory-roadmap completion is still surfaced, but only as informational context in
 * {@link InterviewRequirementReadiness#note()} once the requirement is otherwise measurable - never as
 * part of the score, and never as the reason something becomes measurable.
 */
class ProblemSolvingInterviewReadinessResolver implements InterviewRequirementReadinessResolver {

    /** The only problem-solving material key this resolver currently knows how to resolve. An
     *  unrecognized key must not silently fall through to the standard dashboard (see
     *  {@link RevolutJavaInterviewProfile}, which references this constant directly so the two never
     *  drift apart). */
    static final String SUPPORTED_KEY = "problem-solving-training";

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
        String key = requirement.getReference().key();
        if (!SUPPORTED_KEY.equals(key)) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "Unsupported problem-solving material key '" + key + "'; only '" + SUPPORTED_KEY + "' is resolvable.");
        }

        ProblemDashboard dashboard = problemDashboardService.build();
        return fromQualityMetrics(requirement, dashboard.qualityMetrics(), dashboard.coreProgress());
    }

    /**
     * Pure aggregation over already-loaded {@link ProblemDashboard.QualityMetrics}/
     * {@link ProblemDashboard.CoreProgress}, independent of the database so every branch is directly
     * unit testable.
     *
     * <p><b>Measurable</b> only once {@link ProblemDashboard.QualityMetrics#hasFirstSubmissionSignal()}
     * holds, i.e. at least {@code ProblemDashboard.MIN_SAMPLE_SIZE} (3) real first-submission attempts
     * exist - the same bar the dashboard itself uses before treating a rate as a trend rather than
     * noise.
     *
     * <p><b>Score formula</b>: the average of
     * {@link ProblemDashboard.QualityMetrics#firstSubmissionAccuracyPercent()} (how often the very
     * first submission was already correct - the closest existing proxy for unaided interview
     * performance) and {@link ProblemDashboard.QualityMetrics#independentSolveRatePercent()} (the
     * share of solves credited as {@code SolvedWith.SELF} rather than {@code EDITORIAL}) - but only
     * once {@link ProblemDashboard.QualityMetrics#hasIndependenceSignal()} also holds; below that,
     * scoring is first-submission accuracy alone, following the same "average only the currently
     * measurable signals, never treat a missing one as zero" rule this slice already applies when
     * averaging a domain's requirements. This means a user who solves accurately but leans heavily on
     * editorial solutions scores lower than an equally accurate, independent solver - assisted solving
     * can never look equivalent to independent solving.
     *
     * <p>Mandatory-roadmap completion never contributes to the score; it is quoted in {@link InterviewRequirementReadiness#note()}
     * purely as progress context.
     */
    static InterviewRequirementReadiness fromQualityMetrics(InterviewRequirement requirement,
                                                             ProblemDashboard.QualityMetrics qualityMetrics,
                                                             ProblemDashboard.CoreProgress coreProgress) {
        String roadmapContext = "Roadmap progress (context only, not part of the score): "
                + coreProgress.mandatoryCompleted() + "/" + coreProgress.mandatoryTotal() + " mandatory problems solved.";

        if (!qualityMetrics.hasFirstSubmissionSignal()) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.PROBLEM_SOLVING,
                    "Only " + qualityMetrics.firstSubmissionSampleCount() + " real first-submission attempt(s) so far; "
                            + "need at least " + ProblemDashboard.MIN_SAMPLE_SIZE + " before problem-solving quality is measurable. "
                            + roadmapContext);
        }

        double score;
        String basis;
        if (qualityMetrics.hasIndependenceSignal()) {
            score = (qualityMetrics.firstSubmissionAccuracyPercent() + qualityMetrics.independentSolveRatePercent()) / 2.0;
            basis = "average of first-submission accuracy (" + Math.round(qualityMetrics.firstSubmissionAccuracyPercent())
                    + "%, n=" + qualityMetrics.firstSubmissionSampleCount() + ") and independent-solve rate ("
                    + Math.round(qualityMetrics.independentSolveRatePercent()) + "%, n=" + qualityMetrics.independenceSampleCount() + ")";
        } else {
            score = qualityMetrics.firstSubmissionAccuracyPercent();
            basis = "first-submission accuracy only (" + Math.round(qualityMetrics.firstSubmissionAccuracyPercent())
                    + "%, n=" + qualityMetrics.firstSubmissionSampleCount()
                    + "); independent-solve rate not yet measurable (n=" + qualityMetrics.independenceSampleCount()
                    + ", need " + ProblemDashboard.MIN_SAMPLE_SIZE + ")";
        }

        return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.PROBLEM_SOLVING, score,
                "Problem-solving quality signal: " + basis + ". " + roadmapContext);
    }
}
