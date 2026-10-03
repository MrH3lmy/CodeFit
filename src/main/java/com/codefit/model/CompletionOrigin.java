package com.codefit.model;

/**
 * How a {@link ProblemAttempt} knows what it knows about when it happened (#183 review fix).
 * {@code submissionResult} alone (AC/ACX) cannot answer this: {@code ACX} is overloaded to also
 * mean "the learner already solved this before using CodeFit, at an unknown time" ({@code
 * ProblemSolvingWorkspaceService#markPreviouslySolved}), which looks identical to a genuine
 * first-time accepted-after-failures solve unless the attempt itself says which one it is.
 *
 * <p>Two independent questions this answers, not always with the same answer:
 * <ol>
 *   <li><b>Is {@code submittedAt} trustworthy evidence that something genuinely happened at that
 *       moment</b> ("window-attribution"), used by attempt/accepted/solving-time volume metrics?
 *       True for {@link #FRESH_ATTEMPT} and {@link #PREVIOUSLY_SOLVED} (both are recorded at the
 *       real moment the learner acted, even though {@code PREVIOUSLY_SOLVED}'s own prior solve
 *       happened earlier at an unknown time); true for {@link #UNKNOWN} too, since those rows'
 *       {@code submittedAt} is whatever the app genuinely wrote when they predate this column, not
 *       a fabricated value. False only for {@link #IMPORTED}: the workbook importer's {@code
 *       submittedAt} is the import's own run time, not when the learner actually did anything, so
 *       counting it as "activity in {whatever window the import happened to run in}" would
 *       fabricate evidence just as surely as counting it as a completion would.</li>
 *   <li><b>Is this attempt trustworthy evidence of the problem's FIRST-EVER completion</b>, used
 *       only by {@code problem.unique_completed}? True only for {@link #FRESH_ATTEMPT}.</li>
 * </ol>
 */
public enum CompletionOrigin {
    /**
     * Recorded by the real-time solving workspace ({@code ProblemSolvingWorkspaceService#finish}):
     * {@code submittedAt} is trustworthy evidence of when this attempt actually happened, whatever
     * verdict it carries. Trustworthy for both window-attribution and first-completion evidence.
     */
    FRESH_ATTEMPT,
    /**
     * Recorded by {@code ProblemSolvingWorkspaceService#markPreviouslySolved}: the learner already
     * solved this problem before using CodeFit, at a time CodeFit has no record of.
     * {@code submittedAt} is still a real, trustworthy timestamp of today's mark-previously-solved
     * action itself (so attempt/acceptance volume still counts it), but it must never be treated as
     * first-completion evidence - that would fabricate "completed today" for a completion that may
     * have happened months or years earlier.
     */
    PREVIOUSLY_SOLVED,
    /**
     * Recorded by the workbook importer ({@code TrainingSheetImportService}): {@code submittedAt}
     * is the import's own run timestamp, not the learner's real historical submission time (which
     * the workbook never carries). Untrustworthy for BOTH questions above - never counted as a
     * first-completion, and never counted as attempt/accepted/solving-time activity that happened
     * in whatever window the import happened to run in, since no evidence says it actually did.
     */
    IMPORTED,
    /**
     * Ambiguous pre-migration evidence: a row that predates this column entirely. Before this
     * field existed, both genuine workspace attempts and workbook-imported historical attempts were
     * stored in the same table, and imported rows were stamped with the import run's current time.
     * There is no durable foreign key from an attempt back to its import batch, so an upgraded row
     * cannot be classified safely after the fact. UNKNOWN is therefore trusted for neither window
     * attribution nor first-completion evidence; a window containing it must report the affected
     * activity metrics as UNAVAILABLE rather than guess, silently drop evidence into a measured zero,
     * or count an import timestamp as real learner activity.
     */
    UNKNOWN
}
