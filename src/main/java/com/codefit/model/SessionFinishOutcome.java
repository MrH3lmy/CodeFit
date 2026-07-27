package com.codefit.model;

/**
 * How a {@link ProblemSolvingSession} was finished (#145), distinct from a judge's
 * {@link SubmissionResult} verdict: {@code ACCEPTED} always maps to {@link SubmissionResult#AC};
 * {@code SUBMITTED} and {@code COULD_NOT_SOLVE} represent a genuine submission whose specific judge
 * verdict is recorded separately. {@code ABANDONED} means no genuine attempt occurred at all (the
 * learner left without really engaging), so it never creates a {@link ProblemAttempt} — see
 * {@code ProblemSolvingWorkspaceService#finish} for the full mapping.
 */
public enum SessionFinishOutcome {
    SUBMITTED,
    ACCEPTED,
    COULD_NOT_SOLVE,
    ABANDONED
}
