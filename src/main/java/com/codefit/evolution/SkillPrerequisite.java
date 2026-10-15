package com.codefit.evolution;

import java.util.Objects;

/** Directed prerequisite -> dependent relationship within a particular SkillGraph. */
public record SkillPrerequisite(SkillRef prerequisite, SkillRef dependent) {
    public SkillPrerequisite {
        Objects.requireNonNull(prerequisite, "Prerequisite reference is required");
        Objects.requireNonNull(dependent, "Dependent reference is required");
        if (prerequisite.skillId().equals(dependent.skillId())) {
            throw new IllegalArgumentException("Skill cannot depend on itself: " + prerequisite.skillId());
        }
    }
}
