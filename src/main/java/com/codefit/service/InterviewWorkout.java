package com.codefit.service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One day's interview-focused training bundle. The workout deliberately composes CodeFit's existing
 * adaptive review queue and guided problem recommendation instead of creating a second scheduler or
 * problem-progress system. The only new content here is the interview orchestration around those
 * existing sources: one technical explanation prompt, one alternating design/failure drill, and one
 * reflection prompt.
 */
public record InterviewWorkout(
        String profileId,
        String profileTitle,
        LocalDate date,
        InterviewReadinessResult readiness,
        int reviewSessionMinutes,
        ReviewService.AdaptiveSessionPlan reviewPlan,
        int codingTargetMinutes,
        Optional<ProblemLibraryEntry> codingProblem,
        String codingReason,
        Prompt technicalDeepDive,
        Prompt scenarioDrill,
        Prompt reflection
) {
    public InterviewWorkout {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("Interview workout profile id is required.");
        }
        if (profileTitle == null || profileTitle.isBlank()) {
            throw new IllegalArgumentException("Interview workout profile title is required.");
        }
        Objects.requireNonNull(date, "Interview workout date is required.");
        Objects.requireNonNull(readiness, "Interview workout readiness is required.");
        Objects.requireNonNull(reviewPlan, "Interview workout review plan is required.");
        Objects.requireNonNull(codingProblem, "Interview workout coding problem Optional is required.");
        Objects.requireNonNull(codingReason, "Interview workout coding reason is required.");
        Objects.requireNonNull(technicalDeepDive, "Interview workout technical prompt is required.");
        Objects.requireNonNull(scenarioDrill, "Interview workout scenario prompt is required.");
        Objects.requireNonNull(reflection, "Interview workout reflection prompt is required.");
        if (reviewSessionMinutes < 0 || codingTargetMinutes < 0) {
            throw new IllegalArgumentException("Interview workout block durations cannot be negative.");
        }
    }

    public int reviewCardCount() {
        return reviewPlan.cards().size();
    }

    public boolean hasReviewWork() {
        return !reviewPlan.cards().isEmpty();
    }

    public boolean hasCodingProblem() {
        return codingProblem.isPresent();
    }

    public int totalTargetMinutes() {
        return reviewSessionMinutes + codingTargetMinutes + technicalDeepDive.targetMinutes()
                + scenarioDrill.targetMinutes() + reflection.targetMinutes();
    }

    public enum PromptType {
        TECHNICAL_DEEP_DIVE,
        SYSTEM_DESIGN,
        FAILURE_SCENARIO,
        REFLECTION
    }

    /**
     * A verbal/written drill inside the workout. {@code domainId}/{@code requirementId} are null only
     * for the cross-cutting reflection prompt. {@code backingMaterialAvailable} tells a future UI
     * whether the prompt is anchored to an existing CodeFit deck/subsystem or to a planned profile
     * requirement; the prompt itself remains actionable either way.
     */
    public record Prompt(
            PromptType type,
            String domainId,
            String domainTitle,
            String requirementId,
            String title,
            String instruction,
            int targetMinutes,
            boolean backingMaterialAvailable,
            String sourceReferenceKey
    ) {
        public Prompt {
            Objects.requireNonNull(type, "Interview workout prompt type is required.");
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Interview workout prompt title is required.");
            }
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("Interview workout prompt instruction is required.");
            }
            if (targetMinutes < 0) {
                throw new IllegalArgumentException("Interview workout prompt duration cannot be negative.");
            }
            if (type != PromptType.REFLECTION && (domainId == null || domainId.isBlank())) {
                throw new IllegalArgumentException("Domain-backed interview workout prompts require a domain id.");
            }
        }
    }
}
