package com.codefit.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One generated mock interview. A plan is immutable and contains every prompt plus the scoring
 * rubric needed to grade the run later; no timer/session state is persisted here.
 */
public record InterviewMockPlan(
        String profileId,
        String profileTitle,
        InterviewMockMode mode,
        List<Stage> stages
) {
    public InterviewMockPlan {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("Interview mock profile id is required.");
        }
        if (profileTitle == null || profileTitle.isBlank()) {
            throw new IllegalArgumentException("Interview mock profile title is required.");
        }
        Objects.requireNonNull(mode, "Interview mock mode is required.");
        Objects.requireNonNull(stages, "Interview mock stages are required.");
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("Interview mock plan must contain at least one stage.");
        }

        Set<String> stageIds = new HashSet<>();
        Set<String> criterionIds = new HashSet<>();
        int stageWeightTotal = 0;
        for (Stage stage : stages) {
            if (!stageIds.add(stage.id())) {
                throw new IllegalArgumentException("Duplicate interview mock stage id: " + stage.id());
            }
            stageWeightTotal += stage.weightPercent();
            for (RubricCriterion criterion : stage.rubric()) {
                if (!criterionIds.add(criterion.id())) {
                    throw new IllegalArgumentException("Duplicate interview mock rubric criterion id: " + criterion.id());
                }
            }
        }
        if (stageWeightTotal != 100) {
            throw new IllegalArgumentException("Interview mock stage weights must sum to exactly 100%, got "
                    + stageWeightTotal + "%.");
        }
    }

    public int totalTargetMinutes() {
        return stages.stream().mapToInt(Stage::targetMinutes).sum();
    }

    public enum StageType {
        LIVE_CODING,
        TECHNICAL_DEEP_DIVE,
        SYSTEM_DESIGN,
        TEAM_FIT
    }

    public record Stage(
            String id,
            StageType type,
            String title,
            String prompt,
            int targetMinutes,
            int weightPercent,
            Optional<ProblemLibraryEntry> codingProblem,
            List<RubricCriterion> rubric
    ) {
        public Stage {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Interview mock stage id is required.");
            }
            Objects.requireNonNull(type, "Interview mock stage type is required.");
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Interview mock stage title is required.");
            }
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("Interview mock stage prompt is required.");
            }
            if (targetMinutes <= 0) {
                throw new IllegalArgumentException("Interview mock stage duration must be positive.");
            }
            if (weightPercent <= 0 || weightPercent > 100) {
                throw new IllegalArgumentException("Interview mock stage weight must be between 1 and 100.");
            }
            Objects.requireNonNull(codingProblem, "Interview mock coding-problem Optional is required.");
            Objects.requireNonNull(rubric, "Interview mock rubric is required.");
            rubric = List.copyOf(rubric);
            if (rubric.isEmpty()) {
                throw new IllegalArgumentException("Interview mock stage must contain at least one rubric criterion.");
            }
            int criterionWeightTotal = rubric.stream().mapToInt(RubricCriterion::weightPercent).sum();
            if (criterionWeightTotal != 100) {
                throw new IllegalArgumentException("Interview mock rubric weights for stage '" + id
                        + "' must sum to exactly 100%, got " + criterionWeightTotal + "%.");
            }
            if (type != StageType.LIVE_CODING && codingProblem.isPresent()) {
                throw new IllegalArgumentException("Only a live-coding mock stage may carry a coding problem.");
            }
        }
    }

    /**
     * One observable interview behavior. The domain id is what turns a scored mock from a generic
     * percentage into evidence the readiness engine can reason about.
     */
    public record RubricCriterion(
            String id,
            String title,
            String description,
            String domainId,
            int weightPercent
    ) {
        public RubricCriterion {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Interview mock rubric criterion id is required.");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Interview mock rubric criterion title is required.");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Interview mock rubric criterion description is required.");
            }
            if (domainId == null || domainId.isBlank()) {
                throw new IllegalArgumentException("Interview mock rubric criterion domain id is required.");
            }
            if (weightPercent <= 0 || weightPercent > 100) {
                throw new IllegalArgumentException("Interview mock rubric criterion weight must be between 1 and 100.");
            }
        }
    }
}
