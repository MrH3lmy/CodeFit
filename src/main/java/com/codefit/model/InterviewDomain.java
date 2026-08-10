package com.codefit.model;

import java.util.List;
import java.util.Objects;

/**
 * One evaluation area within an {@link InterviewPreparationProfile} (e.g. "Java Concurrency & Java
 * Memory Model"), carrying its scoring weight, whether it is a critical gate, and the
 * {@link InterviewRequirement}s it draws on. This models the information a later readiness-scoring
 * engine will need; it does not compute a score itself.
 */
public class InterviewDomain {
    private final String id;
    private final String title;
    private final String description;
    private final int weightPercent;
    private final boolean criticalGate;
    private final Integer minimumReadinessThresholdPercent;
    private final List<InterviewRequirement> requirements;

    public InterviewDomain(String id, String title, String description, int weightPercent, boolean criticalGate,
                           Integer minimumReadinessThresholdPercent, List<InterviewRequirement> requirements) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Interview domain id is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Interview domain title is required.");
        }
        if (weightPercent < 0 || weightPercent > 100) {
            throw new IllegalArgumentException("Interview domain weight must be between 0 and 100: " + weightPercent);
        }
        if (criticalGate && minimumReadinessThresholdPercent == null) {
            throw new IllegalArgumentException(
                    "Critical interview domain '" + id + "' must declare a minimum readiness threshold.");
        }
        if (minimumReadinessThresholdPercent != null
                && (minimumReadinessThresholdPercent < 0 || minimumReadinessThresholdPercent > 100)) {
            throw new IllegalArgumentException(
                    "Interview domain minimum readiness threshold must be between 0 and 100: " + minimumReadinessThresholdPercent);
        }
        Objects.requireNonNull(requirements, "Interview domain requirements are required.");
        this.id = id;
        this.title = title;
        this.description = description;
        this.weightPercent = weightPercent;
        this.criticalGate = criticalGate;
        this.minimumReadinessThresholdPercent = minimumReadinessThresholdPercent;
        this.requirements = List.copyOf(requirements);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }

    /** Contribution of this domain to the overall readiness score, as whole percentage points (0-100). */
    public int getWeightPercent() { return weightPercent; }

    /**
     * Whether a candidate can be "not ready" overall purely because of this domain, regardless of
     * how high the total weighted score is. The actual not-ready decision belongs to a later slice's
     * scoring engine; this only marks which domains must gate it.
     */
    public boolean isCriticalGate() { return criticalGate; }

    /** Minimum score (0-100) this domain must clear, or {@code null} when none is set. */
    public Integer getMinimumReadinessThresholdPercent() { return minimumReadinessThresholdPercent; }

    public boolean hasMinimumReadinessThreshold() { return minimumReadinessThresholdPercent != null; }

    public List<InterviewRequirement> getRequirements() { return requirements; }
}
