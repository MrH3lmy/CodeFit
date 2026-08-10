package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.InterviewRequirementStatus;

/**
 * How ready one {@link InterviewRequirement} is, resolved from existing CodeFit progress data (or
 * left unmeasured when it can't be). The compact constructor enforces {@code measurable} and
 * {@code scorePercent} agree - a measurable requirement always has a score and an unmeasurable one
 * never does - so {@link #planned}/{@link #unmeasurable}/{@link #measured} are the only ways to reach
 * a consistent instance, the same invariant-by-construction approach {@code InterviewRequirement}
 * itself uses.
 *
 * @param scorePercent whole-percentage-points score (0-100), or {@code null} when not measurable
 * @param note human-readable explanation of the score, or of why there isn't one
 */
public record InterviewRequirementReadiness(
        String requirementId,
        String requirementTitle,
        InterviewRequirementStatus requirementStatus,
        InterviewMaterialType sourceType,
        boolean measurable,
        Integer scorePercent,
        String note
) {
    public InterviewRequirementReadiness {
        if (measurable && scorePercent == null) {
            throw new IllegalArgumentException("Measurable requirement readiness '" + requirementId + "' must have a score.");
        }
        if (!measurable && scorePercent != null) {
            throw new IllegalArgumentException("Non-measurable requirement readiness '" + requirementId + "' must not have a score.");
        }
        if (scorePercent != null && (scorePercent < 0 || scorePercent > 100)) {
            throw new IllegalArgumentException("Requirement readiness '" + requirementId
                    + "' score must be between 0 and 100, got " + scorePercent
                    + " - a resolver must never produce an out-of-range score, and this is never silently clamped.");
        }
    }

    /** A {@code PLANNED} requirement: no backing material exists yet, so there is nothing to measure. */
    static InterviewRequirementReadiness planned(InterviewRequirement requirement) {
        return new InterviewRequirementReadiness(requirement.getId(), requirement.getTitle(), requirement.getStatus(),
                null, false, null, "Planned - no content or scoring source exists yet.");
    }

    /** An {@code AVAILABLE} requirement whose material exists but currently has no usable data (e.g. no cards yet). */
    static InterviewRequirementReadiness unmeasurable(InterviewRequirement requirement, InterviewMaterialType sourceType, String reason) {
        return new InterviewRequirementReadiness(requirement.getId(), requirement.getTitle(), requirement.getStatus(),
                sourceType, false, null, reason);
    }

    /** An {@code AVAILABLE} requirement resolved to a real score, rounded to whole percentage points. */
    static InterviewRequirementReadiness measured(InterviewRequirement requirement, InterviewMaterialType sourceType,
                                                   double scorePercent, String note) {
        return new InterviewRequirementReadiness(requirement.getId(), requirement.getTitle(), requirement.getStatus(),
                sourceType, true, (int) Math.round(scorePercent), note);
    }
}
