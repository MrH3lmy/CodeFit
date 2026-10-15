package com.codefit.evolution;

/** Exact historical definition identity; never resolves implicitly to a latest version. */
public record SkillRef(String skillId, int definitionVersion) implements Comparable<SkillRef> {
    public SkillRef {
        requireIdentifier(skillId, "Skill ID");
        if (definitionVersion < 1) throw new IllegalArgumentException("Definition version must be positive: " + definitionVersion);
    }

    static void requireIdentifier(String value, String label) {
        if (value == null || !value.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalArgumentException(label + " must be a lowercase stable identifier: " + value);
        }
    }

    @Override
    public int compareTo(SkillRef other) {
        int idOrder = skillId.compareTo(other.skillId);
        return idOrder != 0 ? idOrder : Integer.compare(definitionVersion, other.definitionVersion);
    }
}
