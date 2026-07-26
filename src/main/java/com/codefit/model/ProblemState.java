package com.codefit.model;

/**
 * The current solving state of a {@link Problem}, tracked on its single {@link ProblemProgress}
 * record. Distinct from a {@link ProblemAttempt}'s {@link SubmissionResult}: state summarizes where
 * the learner currently stands on the problem, while a submission result is the objective verdict of
 * one individual attempt.
 */
public enum ProblemState {
    NOT_STARTED,
    IN_PROGRESS,
    SOLVED,
    NEEDS_REVISIT
}
