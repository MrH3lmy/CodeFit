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
 */
public record ProblemAttempt(long id, long problemId, int attemptNumber, SubmissionResult submissionResult,
                              Integer readingTimeSeconds, Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                              Integer debuggingTimeSeconds, LocalDateTime submittedAt, String notes) {
}
