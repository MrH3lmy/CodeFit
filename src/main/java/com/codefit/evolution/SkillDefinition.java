package com.codefit.evolution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** A capability definition, containing no learner state, mastery, XP or curriculum ordering. */
public record SkillDefinition(SkillRef ref, String domainId, String name, String description,
                              List<String> observableBehaviors, SkillLifecycle lifecycle) {
    public SkillDefinition {
        Objects.requireNonNull(ref, "Skill reference is required");
        SkillRef.requireIdentifier(domainId, "Domain ID");
        requireText(name, "Skill name");
        requireText(description, "Skill description");
        Objects.requireNonNull(lifecycle, "Skill lifecycle is required");
        observableBehaviors = List.copyOf(Objects.requireNonNull(observableBehaviors, "Observable behaviors are required"));
        if (observableBehaviors.isEmpty()) throw new IllegalArgumentException("At least one observable behavior is required for " + ref);
        for (String behavior : observableBehaviors) requireText(behavior, "Observable behavior");
        if (new HashSet<>(observableBehaviors).size() != observableBehaviors.size()) {
            throw new IllegalArgumentException("Duplicate observable behavior for " + ref);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
