package com.codefit.model;

import java.time.Instant;
import java.util.Objects;

/** Prospective facts only. Historical absence means unknown, never inferred from today's item. */
public record AssessmentAttemptFacts(
        String gradeKind,
        String validator,
        String gradingVersion,
        long variantId,
        int variantIndex,
        String variantSha,
        String presentationId,
        Instant presentedAt,
        Instant submittedAt,
        boolean hintUsed,
        boolean solutionRevealed,
        int priorItemAttempts,
        String exposure) {
    public AssessmentAttemptFacts {
        Objects.requireNonNull(gradeKind);
        Objects.requireNonNull(validator);
        Objects.requireNonNull(variantSha);
        Objects.requireNonNull(presentationId);
        Objects.requireNonNull(presentedAt);
        Objects.requireNonNull(submittedAt);
        if (presentedAt.isAfter(submittedAt)
                || variantIndex < 0
                || priorItemAttempts < 0
                || !variantSha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid prospective facts");
    }
}
