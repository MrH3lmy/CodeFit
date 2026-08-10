package com.codefit.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Durable outcome of one completed mock interview. */
public record InterviewMockEvaluation(
        String runId,
        String profileId,
        InterviewMockMode mode,
        int overallScorePercent,
        LocalDateTime completedAt,
        String notes,
        List<StageScore> stageScores,
        List<DomainScore> domainScores
) {
    public InterviewMockEvaluation {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Interview mock run id is required.");
        }
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("Interview mock profile id is required.");
        }
        Objects.requireNonNull(mode, "Interview mock mode is required.");
        if (overallScorePercent < 0 || overallScorePercent > 100) {
            throw new IllegalArgumentException("Interview mock overall score must be between 0 and 100.");
        }
        Objects.requireNonNull(completedAt, "Interview mock completion time is required.");
        stageScores = List.copyOf(stageScores);
        domainScores = List.copyOf(domainScores);

        Set<String> stageIds = new HashSet<>();
        for (StageScore stageScore : stageScores) {
            if (!stageIds.add(stageScore.stageId())) {
                throw new IllegalArgumentException("Duplicate interview mock stage score id: " + stageScore.stageId());
            }
        }
        Set<String> domainIds = new HashSet<>();
        for (DomainScore domainScore : domainScores) {
            if (!domainIds.add(domainScore.domainId())) {
                throw new IllegalArgumentException("Duplicate interview mock domain score id: " + domainScore.domainId());
            }
        }
    }

    public record StageScore(String stageId, InterviewMockPlan.StageType stageType, int scorePercent) {
        public StageScore {
            if (stageId == null || stageId.isBlank()) {
                throw new IllegalArgumentException("Interview mock stage score id is required.");
            }
            Objects.requireNonNull(stageType, "Interview mock stage score type is required.");
            if (scorePercent < 0 || scorePercent > 100) {
                throw new IllegalArgumentException("Interview mock stage score must be between 0 and 100.");
            }
        }
    }

    public record DomainScore(String domainId, int scorePercent) {
        public DomainScore {
            if (domainId == null || domainId.isBlank()) {
                throw new IllegalArgumentException("Interview mock domain score id is required.");
            }
            if (scorePercent < 0 || scorePercent > 100) {
                throw new IllegalArgumentException("Interview mock domain score must be between 0 and 100.");
            }
        }
    }
}
