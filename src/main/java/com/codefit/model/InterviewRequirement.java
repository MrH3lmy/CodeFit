package com.codefit.model;

import java.util.Objects;

/**
 * One concrete piece of learning material an {@link InterviewDomain} draws on: either existing
 * CodeFit material (status {@code AVAILABLE}, resolvable via {@link #getReference()}) or a module
 * planned for a later slice that has no content yet (status {@code PLANNED}, no reference). This is
 * how an interview profile composes existing training-path content instead of duplicating it.
 *
 * <p>The constructor is private specifically so {@code AVAILABLE} + no reference and
 * {@code PLANNED} + a reference can never be constructed - {@link #available} and {@link #planned}
 * are the only ways to build one, and each can only produce a consistent state.
 */
public class InterviewRequirement {
    private final String id;
    private final String title;
    private final String description;
    private final InterviewRequirementStatus status;
    private final InterviewMaterialReference reference;

    private InterviewRequirement(String id, String title, String description, InterviewRequirementStatus status,
                                 InterviewMaterialReference reference) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Interview requirement id is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Interview requirement title is required.");
        }
        Objects.requireNonNull(status, "Interview requirement status is required.");
        if (status == InterviewRequirementStatus.AVAILABLE && reference == null) {
            throw new IllegalArgumentException("Available interview requirement '" + id + "' must reference existing material.");
        }
        if (status == InterviewRequirementStatus.PLANNED && reference != null) {
            throw new IllegalArgumentException("Planned interview requirement '" + id + "' must not reference material yet.");
        }
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.reference = reference;
    }

    /** An available requirement resolvable to existing CodeFit material of the given type. */
    public static InterviewRequirement available(String id, String title, String description,
                                                  InterviewMaterialType type, String key) {
        return new InterviewRequirement(id, title, description, InterviewRequirementStatus.AVAILABLE,
                new InterviewMaterialReference(type, key));
    }

    /** A requirement for material that does not exist yet; a later slice will build it. */
    public static InterviewRequirement planned(String id, String title, String description) {
        return new InterviewRequirement(id, title, description, InterviewRequirementStatus.PLANNED, null);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public InterviewRequirementStatus getStatus() { return status; }

    /** How to resolve this requirement's existing material, or {@code null} when {@link #isAvailable()} is false. */
    public InterviewMaterialReference getReference() { return reference; }

    public boolean isAvailable() { return status == InterviewRequirementStatus.AVAILABLE; }
}
