package com.codefit.model;

import java.util.Objects;

/**
 * Where an {@code AVAILABLE} {@link InterviewRequirement}'s material actually lives: a typed source
 * plus the stable key to resolve it by (a deck name for {@link InterviewMaterialType#DECK}). This
 * exists so a later readiness-scoring slice can resolve real progress/mastery data instead of
 * parsing free-form reference text.
 */
public record InterviewMaterialReference(InterviewMaterialType type, String key) {
    public InterviewMaterialReference {
        Objects.requireNonNull(type, "Interview material reference type is required.");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Interview material reference key is required.");
        }
    }
}
