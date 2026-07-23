package com.codefit.model;

/**
 * Objective telemetry captured for a single review attempt, independent of the learner's
 * self-selected scheduler rating.
 */
public record ReviewAttempt(String validationResult, String submittedAnswer, Integer responseTimeMs,
                             boolean hintUsed, String sessionId) {
}
