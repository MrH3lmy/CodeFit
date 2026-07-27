package com.codefit.model;

/**
 * Which part of a solved problem's post-solve reflection (#146) a flashcard was generated from
 * (#148). Paired with a problem id on {@link Flashcard#getSourceProblemId()}/
 * {@link Flashcard#getSourceReflectionField()} to detect "a card from this exact reflection field of
 * this exact problem already exists" before creating another one.
 *
 * <p>Five of the six sources have a direct backing {@link ProblemProgress} field the answer is
 * pre-filled from; {@link #EDGE_CASE} has no dedicated stored field (no edge-case text is captured
 * elsewhere), so its draft starts blank for the learner to write from scratch — there was never
 * existing text to avoid retyping for that one.
 */
public enum ReflectionCardSource {
    LESSON_LEARNED,
    MISTAKE_MADE,
    KEY_OBSERVATION,
    ALGORITHM_OR_TECHNIQUE,
    COMPLEXITY_TRADEOFF,
    EDGE_CASE
}
