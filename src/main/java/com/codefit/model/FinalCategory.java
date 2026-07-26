package com.codefit.model;

/**
 * The learner's final self-assessed mastery of a problem once it reaches a resting state, recorded
 * on {@link ProblemProgress}. Distinct from {@link SolvedWith} (how much help was used) and
 * {@link ProblemState} (where the problem currently sits in the workflow): this is a coaching signal
 * for #147-style dashboards to surface topics that need more repetition even if the problem was
 * technically solved.
 */
public enum FinalCategory {
    STRONG,
    SHAKY,
    WEAK
}
