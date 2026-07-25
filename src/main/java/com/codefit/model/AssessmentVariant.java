package com.codefit.model;

/**
 * One scenario/wording of an {@link AssessmentItem}: same underlying concept as another variant of
 * the same item, but a different situation, parameter, or phrasing (a new concurrency race instead
 * of repeating the optimistic-locking definition, a different exception/propagation combination,
 * etc.). Rotating which variant is served keeps a repeated weekly assessment from ever showing the
 * learner the exact wording they already answered (#104).
 */
public record AssessmentVariant(long id, int variantIndex, String scenario, String acceptedAnswers,
                                 String referenceAnswer, String simulatedOutput, String hint) {

    public boolean hasHint() {
        return hint != null && !hint.isBlank();
    }
}
