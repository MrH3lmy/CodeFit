package com.codefit.model;

import java.time.LocalDateTime;

/**
 * A single, immutable submission attempt at a {@link Problem}. A problem can have many attempts
 * (unlike its one {@link ProblemProgress} record); {@code attemptNumber} is a 1-based sequence
 * scoped to the problem, unique together with {@code problemId} so replaying an import can never
 * duplicate an attempt (see {@code problem_attempts}'s unique constraint).
 *
 * <p>The four phase-time fields mirror {@link SolvingPhase} and are independent, optional
 * measurements (seconds); a caller that only timed part of the workflow simply leaves the rest null
 * rather than needing a placeholder value.
 *
 * <p>{@code sessionOutcome} is set only for attempts created by finishing a
 * {@link ProblemSolvingSession} (#145) — it records the learner's own finish reason
 * ({@code SUBMITTED}/{@code ACCEPTED}/{@code COULD_NOT_SOLVE}), which is a different axis from
 * {@code submissionResult}'s judge verdict (e.g. a {@code SUBMITTED} finish can carry any verdict,
 * not just a successful one). It is {@code null} for attempts recorded any other way.
 */
public record ProblemAttempt(long id, long problemId, int attemptNumber, SubmissionResult submissionResult,
                              Integer readingTimeSeconds, Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                              Integer debuggingTimeSeconds, LocalDateTime submittedAt, String notes,
                              SessionFinishOutcome sessionOutcome) {

    public ProblemAttempt(long id, long problemId, int attemptNumber, SubmissionResult submissionResult,
                          Integer readingTimeSeconds, Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                          Integer debuggingTimeSeconds, LocalDateTime submittedAt, String notes) {
        this(id, problemId, attemptNumber, submissionResult, readingTimeSeconds, thinkingTimeSeconds,
                codingTimeSeconds, debuggingTimeSeconds, submittedAt, notes, null);
    }
}
