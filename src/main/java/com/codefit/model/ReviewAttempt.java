package com.codefit.model;

/**
 * Objective telemetry captured for a single review attempt, independent of the learner's
 * self-selected scheduler rating. {@code confidence} is a separate, optional self-report used for
 * later calibration statistics (e.g. "high confidence but incorrect") — it must never be inferred
 * from or conflated with the scheduler rating.
 */
public record ReviewAttempt(String validationResult, String submittedAnswer, Integer responseTimeMs,
                             boolean hintUsed, String sessionId, ConfidenceLevel confidence) {

    public ReviewAttempt(String validationResult, String submittedAnswer, Integer responseTimeMs,
                         boolean hintUsed, String sessionId) {
        this(validationResult, submittedAnswer, responseTimeMs, hintUsed, sessionId, null);
    }
}
