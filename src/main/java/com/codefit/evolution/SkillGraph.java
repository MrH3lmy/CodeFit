package com.codefit.evolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Immutable validated prerequisite DAG. A snapshot pins exactly one version of each included skill.
 * All queries are deterministic and domain-neutral; membership implies no learner proficiency.
 */
public record SkillGraph(GraphRef ref, List<SkillDefinition> definitions, List<SkillPrerequisite> prerequisites) {
    public SkillGraph {
        Objects.requireNonNull(ref, "Graph reference is required");
        definitions = List.copyOf(Objects.requireNonNull(definitions, "Graph definitions are required")).stream().sorted(Comparator.comparing(SkillDefinition::ref)).toList();
        prerequisites = List.copyOf(Objects.requireNonNull(prerequisites, "Graph prerequisites are required")).stream().sorted(Comparator
                .comparing(SkillPrerequisite::prerequisite).thenComparing(SkillPrerequisite::dependent)).toList();
        if (definitions.isEmpty()) throw new IllegalArgumentException("Graph must contain at least one skill: " + ref);
        Map<String, SkillRef> versions = new HashMap<>();
        Set<SkillRef> members = new HashSet<>();
        for (SkillDefinition definition : definitions) {
            SkillRef previous = versions.putIfAbsent(definition.ref().skillId(), definition.ref());
            if (previous != null) throw new IllegalArgumentException("Duplicate or inconsistent skill versions in " + ref + ": " + previous + " and " + definition.ref());
            members.add(definition.ref());
        }
        Set<SkillPrerequisite> edges = new HashSet<>();
        for (SkillPrerequisite edge : prerequisites) {
            if (!edges.add(edge)) throw new IllegalArgumentException("Duplicate prerequisite edge in " + ref + ": " + edge);
            for (SkillRef endpoint : List.of(edge.prerequisite(), edge.dependent())) {
                if (!members.contains(endpoint)) throw new IllegalArgumentException("Missing exact skill definition " + endpoint + " in graph " + ref);
            }
        }
        sortedRefs(definitions, prerequisites); // rejects cycles, even in disconnected components
    }

    public List<SkillDefinition> topologicalOrder() {
        Map<SkillRef, SkillDefinition> byRef = new HashMap<>();
        definitions.forEach(definition -> byRef.put(definition.ref(), definition));
        return sortedRefs(definitions, prerequisites).stream().map(byRef::get).toList();
    }

    /** Direct prerequisites in stable ID order. Unknown/non-member versions are an error. */
    public List<SkillDefinition> prerequisitesOf(SkillRef skill) {
        requireMember(skill);
        Set<SkillRef> refs = new HashSet<>();
        prerequisites.stream().filter(edge -> edge.dependent().equals(skill)).forEach(edge -> refs.add(edge.prerequisite()));
        return definitions.stream().filter(definition -> refs.contains(definition.ref())).toList();
    }

    /** Direct dependents in stable ID order. */
    public List<SkillDefinition> dependentsOf(SkillRef skill) {
        requireMember(skill);
        Set<SkillRef> refs = new HashSet<>();
        prerequisites.stream().filter(edge -> edge.prerequisite().equals(skill)).forEach(edge -> refs.add(edge.dependent()));
        return definitions.stream().filter(definition -> refs.contains(definition.ref())).toList();
    }

    /** All transitive prerequisites, prerequisites first, excluding the requested skill itself. */
    public List<SkillDefinition> prerequisiteTraversal(SkillRef skill) {
        requireMember(skill);
        Set<SkillRef> ancestors = new HashSet<>();
        List<SkillRef> pending = new ArrayList<>(List.of(skill));
        for (int index = 0; index < pending.size(); index++) {
            for (SkillDefinition prerequisite : prerequisitesOf(pending.get(index))) {
                if (ancestors.add(prerequisite.ref())) pending.add(prerequisite.ref());
            }
        }
        return topologicalOrder().stream().filter(definition -> ancestors.contains(definition.ref())).toList();
    }

    private void requireMember(SkillRef skill) {
        if (definitions.stream().noneMatch(definition -> definition.ref().equals(skill))) {
            throw new IllegalArgumentException("Skill " + skill + " is not a member of " + ref);
        }
    }

    private static List<SkillRef> sortedRefs(List<SkillDefinition> definitions, List<SkillPrerequisite> edges) {
        Map<SkillRef, Integer> incoming = new HashMap<>();
        Map<SkillRef, List<SkillRef>> outgoing = new HashMap<>();
        definitions.forEach(definition -> incoming.put(definition.ref(), 0));
        for (SkillPrerequisite edge : edges) {
            incoming.merge(edge.dependent(), 1, Integer::sum);
            outgoing.computeIfAbsent(edge.prerequisite(), ignored -> new ArrayList<>()).add(edge.dependent());
        }
        PriorityQueue<SkillRef> ready = new PriorityQueue<>();
        incoming.forEach((skill, count) -> { if (count == 0) ready.add(skill); });
        List<SkillRef> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            SkillRef skill = ready.remove();
            ordered.add(skill);
            for (SkillRef dependent : outgoing.getOrDefault(skill, List.of())) {
                if (incoming.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        if (ordered.size() != definitions.size()) {
            List<SkillRef> blocked = incoming.keySet().stream().filter(skill -> !ordered.contains(skill)).sorted().toList();
            throw new IllegalArgumentException("Prerequisite cycle in graph; blocked skills: " + blocked);
        }
        return List.copyOf(ordered);
    }
}
