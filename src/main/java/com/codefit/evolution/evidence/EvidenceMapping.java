package com.codefit.evolution.evidence;

import com.codefit.evolution.GraphRef;
import com.codefit.evolution.SkillRef;
import com.codefit.evolution.evidence.EvidenceTypes.SourceType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact contextual category/topic rules, not a grader or evidence of the target skill. */
public record EvidenceMapping(EvidenceMappingRef ref, GraphRef graph, List<Rule> rules) {
    public record Rule(SourceType sourceType, String selector, SkillRef target) {
        public Rule {
            Objects.requireNonNull(sourceType);
            Objects.requireNonNull(target);
            if (selector == null || selector.isBlank() || selector.length() > 128)
                throw new IllegalArgumentException("Exact bounded selector required");
        }
    }

    public EvidenceMapping {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(graph);
        rules =
                List.copyOf(rules).stream()
                        .sorted(
                                Comparator.comparing(Rule::sourceType)
                                        .thenComparing(Rule::selector)
                                        .thenComparing(Rule::target))
                        .toList();
        if (rules.size() > 128 || new HashSet<>(rules).size() != rules.size())
            throw new IllegalArgumentException("Mapping rules must be distinct and bounded");
    }

    public List<SkillRef> targets(SourceType type, String selector) {
        return rules.stream()
                .filter(rule -> rule.sourceType() == type && rule.selector().equals(selector))
                .map(Rule::target)
                .distinct()
                .sorted()
                .toList();
    }
}
