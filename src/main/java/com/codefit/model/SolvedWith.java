package com.codefit.model;

/**
 * How much assistance the learner used to reach a solution, recorded on {@link ProblemProgress}.
 * Ordered here from least to most assisted; {@link com.codefit.service.ProblemProgressService} and
 * later coaching/dashboard work (#147) treat {@link #SELF} as full independence and everything after
 * it as a degree of external help.
 */
public enum SolvedWith {
    SELF,
    HINT,
    EDITORIAL,
    SOLUTION,
    PREVIOUSLY_SOLVED
}
