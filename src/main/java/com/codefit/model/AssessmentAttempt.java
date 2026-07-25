package com.codefit.model;

import java.time.LocalDateTime;

/**
 * A single graded attempt at an {@link AssessmentItem} variant, recorded only in the assessment
 * bank's own {@code assessment_attempts} table. This is never written to {@code review_history} and
 * never used to update a {@link Flashcard}'s schedule: assessment results are deliberately isolated
 * from spaced-repetition scheduling (#104) unless a future, explicit, opt-in concept mapping decides
 * otherwise. {@code runId} groups every attempt made during the same weekly assessment session, the
 * same way {@code sessionId} groups a normal review session in {@link ReviewHistory}.
 */
public record AssessmentAttempt(long id, long assessmentItemId, int variantIndex, String skillCategory,
                                 String moduleName, boolean correct, String submittedAnswer,
                                 Integer responseTimeMs, LocalDateTime attemptedAt, String runId) {
}
