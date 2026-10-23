package com.codefit.knowledge;

import com.codefit.evolution.GraphRef;
import com.codefit.evolution.SkillRef;
import java.util.List;

/** Metadata contract only; deliberately not a challenge, approval, or progression contract. */
public record PublicPreview(
        String exposure, String revision, String reviewStatus, String verification,
        String caveat, List<Skill> skills, List<Mapping> mappings, List<Blocker> blockers) {
    public PublicPreview {
        skills = List.copyOf(skills);
        mappings = List.copyOf(mappings);
        blockers = List.copyOf(blockers);
    }

    public record Skill(String id, String title, List<String> prerequisites, List<Stage> stages) {
        public Skill {
            prerequisites = List.copyOf(prerequisites);
            stages = List.copyOf(stages);
        }
    }
    public record Stage(String stage, String id) { }
    public record Mapping(String id, SkillRef skill, GraphRef graph) { }
    public record Blocker(String code, String id) { }
}
