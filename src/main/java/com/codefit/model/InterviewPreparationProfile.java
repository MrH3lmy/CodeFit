package com.codefit.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A target-company/role interview-preparation profile: a named, weighted set of
 * {@link InterviewDomain}s that compose existing CodeFit learning material (and declare
 * not-yet-built material) rather than duplicating another sequential {@link TrainingPath}.
 *
 * <p>An interview profile is a cross-cutting view over existing training-path/deck content, not
 * another path with prerequisite semantics: a domain's requirements simply reference existing
 * material by name, and nothing here reads or writes {@link TrainingPath} state.
 *
 * <p>Readiness scoring against a profile (whether a candidate is "ready", weighting a high overall
 * score against a failed critical gate, etc.) is intentionally out of scope here - see
 * {@link #validate()} for the structural checks this slice does perform.
 */
public class InterviewPreparationProfile {
    private final String id;
    private final String title;
    private final String description;
    private final List<InterviewDomain> domains;

    public InterviewPreparationProfile(String id, String title, String description, List<InterviewDomain> domains) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Interview profile id is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Interview profile title is required.");
        }
        Objects.requireNonNull(domains, "Interview profile domains are required.");
        this.id = id;
        this.title = title;
        this.description = description;
        this.domains = List.copyOf(domains);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public List<InterviewDomain> getDomains() { return domains; }

    public Optional<InterviewDomain> findDomainById(String domainId) {
        return domains.stream().filter(domain -> domain.getId().equals(domainId)).findFirst();
    }

    /**
     * Structural validation across domains: unique domain ids, unique requirement ids (profile-wide,
     * not just within a domain, since these are the stable identifiers later readiness/progress logic
     * will address requirements by), and weights summing to exactly 100%. (Each {@link InterviewDomain}
     * already enforces its own critical-gate-requires-a-threshold invariant at construction time, so
     * that check cannot fail here.) Weights are whole percentage points specifically so this sum is
     * exact integer arithmetic, never floating-point rounding - an invalid profile is reported here,
     * never silently normalized.
     *
     * @return the violations found, or an empty list when the profile is structurally valid
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();
        if (domains.isEmpty()) {
            violations.add("Profile '" + id + "' has no domains.");
            return List.copyOf(violations);
        }

        Set<String> seenDomainIds = new LinkedHashSet<>();
        Set<String> seenRequirementIds = new LinkedHashSet<>();
        for (InterviewDomain domain : domains) {
            if (!seenDomainIds.add(domain.getId())) {
                violations.add("Duplicate domain id: " + domain.getId());
            }
            for (InterviewRequirement requirement : domain.getRequirements()) {
                if (!seenRequirementIds.add(requirement.getId())) {
                    violations.add("Duplicate requirement id: " + requirement.getId());
                }
            }
        }

        int totalWeightPercent = domains.stream().mapToInt(InterviewDomain::getWeightPercent).sum();
        if (totalWeightPercent != 100) {
            violations.add("Domain weights must sum to exactly 100%, found " + totalWeightPercent + "%.");
        }

        return List.copyOf(violations);
    }

    public boolean isValid() {
        return validate().isEmpty();
    }
}
