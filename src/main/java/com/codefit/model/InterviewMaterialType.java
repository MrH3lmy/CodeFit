package com.codefit.model;

/** What kind of existing CodeFit subsystem an {@link InterviewMaterialReference} points at. */
public enum InterviewMaterialType {
    /** {@link InterviewMaterialReference#key()} is a deck name, matched the same way a {@code TrainingPath.TrainingPathModule} matches decks. */
    DECK,
    /** The existing problem-solving training subsystem (roadmap, attempts, solving workspace) rather than a single deck. */
    PROBLEM_SOLVING
}
