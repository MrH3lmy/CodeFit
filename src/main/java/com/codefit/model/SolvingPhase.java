package com.codefit.model;

/**
 * A phase of the structured problem-solving workflow the Junior Training Sheet prescribes. Both
 * {@link ProblemSolvingSession} (the persistent, resumable in-progress timer) and
 * {@link ProblemAttempt} (the finalized per-submission time breakdown) key their elapsed-time fields
 * to these same four phases.
 */
public enum SolvingPhase {
    READING,
    THINKING,
    CODING,
    DEBUGGING
}
