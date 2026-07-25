package com.codefit.model;

/**
 * A card's lifecycle position, distinct from {@link CardType}. Centralized transitions live in
 * {@code com.codefit.service.CardLifecycleService}.
 */
public enum CardState {
    /** Never reviewed; subject to the daily new-card limit. */
    NEW("New"),
    /** Introduced but not yet graduated into the normal review rotation. */
    LEARNING("Learning"),
    /** Graduated into normal spaced-repetition rotation. */
    REVIEW("Review"),
    /** Was in REVIEW or MASTERED but was just missed and needs reinforcement. */
    RELEARNING("Relearning"),
    /** Excluded from all queues until reactivated. */
    SUSPENDED("Suspended"),
    /** Meets the durable-mastery bar (see MasteryService). */
    MASTERED("Mastered"),
    /**
     * Diagnostically graduated out of a NEW card's normal learning phase via the "Already know
     * this" review action, on the strength of a single correct, unassisted, on-time answer rather
     * than the repeated exposure {@link #MASTERED} requires. Scheduled far into the future (see
     * CardLifecycleService); resurfaces as a normal due card for a retention check once that date
     * arrives, and then transitions like any other reviewed card.
     */
    GRADUATED("Graduated");

    private final String label;

    CardState(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
