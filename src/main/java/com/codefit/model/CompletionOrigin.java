package com.codefit.model;

/**
 * How a {@link ProblemAttempt} knows what it knows about being the problem's first completion
 * (#183 review fix). {@code submissionResult} alone (AC/ACX) cannot answer this: {@code ACX} is
 * overloaded to also mean "the learner already solved this before using CodeFit, at an unknown
 * time" ({@code ProblemSolvingWorkspaceService#markPreviouslySolved}), which looks identical to a
 * genuine first-time accepted-after-failures solve unless the attempt itself says which one it is.
 */
public enum CompletionOrigin {
    /**
     * Recorded by the real-time solving workspace ({@code ProblemSolvingWorkspaceService#finish}):
     * {@code submittedAt} is trustworthy evidence of when this attempt actually happened, whatever
     * verdict it carries.
     */
    FRESH_ATTEMPT,
    /**
     * Recorded by {@code ProblemSolvingWorkspaceService#markPreviouslySolved}: the learner already
     * solved this problem before using CodeFit, at a time CodeFit has no record of. The attempt
     * still counts for attempt/acceptance volume, but it must never be treated as first-completion
     * evidence - that would fabricate "completed today" for a completion that may have happened
     * months or years earlier.
     */
    PREVIOUSLY_SOLVED,
    /**
     * No trustworthy claim either way: a row that predates this column (migrated with no opinion
     * on which path created it - never guessed from {@code submissionResult} alone) or one created
     * by the workbook importer, whose {@code submittedAt} is the import timestamp, not the
     * learner's real original submission time. Conservative default; never treated as
     * first-completion evidence.
     */
    UNKNOWN
}
