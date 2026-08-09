package com.codefit.model;

import java.util.Objects;

/**
 * One concrete piece of learning material an {@link InterviewDomain} draws on: either existing
 * CodeFit material (status {@code AVAILABLE}, e.g. a training-path deck name) or a module planned
 * for a later slice that has no deck/content yet (status {@code PLANNED}). This is how an interview
 * profile composes existing training-path content instead of duplicating it.
 */
public class InterviewRequirement {
    private final String id;
    private final String title;
    private final String description;
    private final InterviewRequirementStatus status;
    private final String reference;

    public InterviewRequirement(String id, String title, String description, InterviewRequirementStatus status,
                                String reference) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Interview requirement id is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Interview requirement title is required.");
        }
        Objects.requireNonNull(status, "Interview requirement status is required.");
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.reference = reference;
    }

    /** An available requirement pointing at existing CodeFit material (a deck name or a named subsystem). */
    public static InterviewRequirement available(String id, String title, String description, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Available interview requirement '" + id + "' must reference existing material.");
        }
        return new InterviewRequirement(id, title, description, InterviewRequirementStatus.AVAILABLE, reference);
    }

    /** A requirement for material that does not exist yet; a later slice will build it. */
    public static InterviewRequirement planned(String id, String title, String description) {
        return new InterviewRequirement(id, title, description, InterviewRequirementStatus.PLANNED, null);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public InterviewRequirementStatus getStatus() { return status; }

    /** The existing deck name or subsystem this requirement reuses, or {@code null} when {@link #isAvailable()} is false. */
    public String getReference() { return reference; }

    public boolean isAvailable() { return status == InterviewRequirementStatus.AVAILABLE; }
}
