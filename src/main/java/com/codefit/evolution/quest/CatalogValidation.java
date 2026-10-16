package com.codefit.evolution.quest;

import com.codefit.evolution.*;

import java.util.*;

/** Pure exact-graph checks; never tests learner prerequisites or grants proficiency. */
public final class CatalogValidation {
    private CatalogValidation() {}

    public static void targets(
            SkillGraph graph,
            String domain,
            List<BehaviorTarget> targets,
            List<SkillRef> prerequisites) {
        Set<SkillRef> expected = new HashSet<>();
        for (var target : targets) {
            var skill =
                    graph.definitions().stream()
                            .filter(s -> s.ref().equals(target.skill()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Target absent from exact graph"));
            if (!skill.domainId().equals(domain)
                    || target.behavior() >= skill.observableBehaviors().size())
                throw new IllegalArgumentException("Invalid domain or behavior target");
            graph.prerequisiteTraversal(target.skill()).forEach(s -> expected.add(s.ref()));
        }
        targets.forEach(t -> expected.remove(t.skill()));
        if (!expected.equals(new HashSet<>(prerequisites)))
            throw new IllegalArgumentException(
                    "Prerequisites must match graph ancestry outside targets");
    }

    public static void quest(EvolutionQuest quest, List<ChallengeSpec> challenges) {
        Set<BehaviorTarget> supported = new HashSet<>();
        Set<String> families = new HashSet<>();
        for (var ref : quest.challenges()) {
            var c =
                    challenges.stream()
                            .filter(x -> x.ref().equals(ref))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Unresolved challenge"));
            if (!c.graph().equals(quest.graph())
                    || !c.domainId().equals(quest.domainId())
                    || c.cohort() != quest.cohort())
                throw new IllegalArgumentException("Quest/challenge boundary mismatch");
            supported.addAll(c.targets());
            families.add(c.familyId());
        }
        if (!supported.containsAll(quest.targets())
                || quest.completion().independentFamilies() > families.size())
            throw new IllegalArgumentException("Unsupported quest completion requirements");
    }
}
