package com.codefit.evolution.quest;

import com.codefit.evolution.SkillRef;

import java.util.Objects;

/** Zero-based behavior index in the exact immutable skill definition. */
public record BehaviorTarget(SkillRef skill, int behavior) implements Comparable<BehaviorTarget> {
    public BehaviorTarget {
        Objects.requireNonNull(skill);
        if (behavior < 0) throw new IllegalArgumentException("Behavior index required");
    }

    @Override
    public int compareTo(BehaviorTarget o) {
        int c = skill.compareTo(o.skill);
        return c == 0 ? Integer.compare(behavior, o.behavior) : c;
    }
}
