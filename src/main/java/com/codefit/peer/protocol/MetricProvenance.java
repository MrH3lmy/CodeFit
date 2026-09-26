package com.codefit.peer.protocol;

/**
 * Where a metric's evidence came from. Signatures prove authorship only; provenance keeps
 * objective, self-reported, and legacy evidence distinguishable so comparisons never mix them
 * silently.
 */
public enum MetricProvenance implements WireCode {
    /** A row CodeFit itself recorded locally (e.g. a review or attempt happened); counts only. */
    LOCAL_RECORD(1),
    /**
     * CodeFit's validator judged the answer (review {@code validation_result} present and
     * objective). Still self-hosted evidence, not proctored.
     */
    VERIFIED_LOCAL_VALIDATION(2),
    /** Learner's Again/Hard/Good/Easy rating on a subjective card. */
    SELF_RATED(3),
    /**
     * Objective card with no stored validation result, so {@code
     * ReviewHistory.isObjectivelyCorrect()} fell back to the self rating. Never counts as verified.
     */
    LEGACY_SELF_RATING_FALLBACK(4),
    /** Learner-recorded outcome such as a problem marked Accepted or a manual judge verdict. */
    LEARNER_REPORTED_OUTCOME(5),
    /** Durations measured by CodeFit's local phase timers. */
    LOCAL_TIMER(6),
    /** Mock-interview score entered/evaluated by the learner. */
    MOCK_SELF_SCORE(7);

    private final int code;

    MetricProvenance(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
