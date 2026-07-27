package com.codefit.model;

/**
 * The four increasingly explicit levels of {@link com.codefit.service.ProblemGuidanceService}'s hint
 * ladder (#162). A learner unlocks these one at a time and in order — never skipping ahead — and the
 * highest level opened so far in the current attempt is tracked on {@link ProblemSolvingSession}.
 */
public enum HintLevel {
    /** Restate the task, constraints, and a question that directs attention. */
    CLARIFY,
    /** Reveal the key property or pattern to notice. */
    OBSERVATION,
    /** Identify the algorithm/data structure and outline the steps. */
    APPROACH,
    /** Full reasoning, correctness intuition, complexity, and common mistakes. */
    EXPLANATION;

    /** The next level in the ladder, or empty once already at {@link #EXPLANATION}. */
    public HintLevel next() {
        HintLevel[] levels = values();
        int nextOrdinal = ordinal() + 1;
        return nextOrdinal < levels.length ? levels[nextOrdinal] : null;
    }
}
