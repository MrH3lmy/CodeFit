package com.codefit.service;

import java.time.LocalDate;
import java.util.List;

/** Summary of one completed weekly transfer assessment run, grouped by the run id every attempt in that session shares. */
public record AssessmentRunSummary(String runId, LocalDate runDate, int totalItems, int correctCount,
                                   List<TransferSkillPerformance> bySkill) {
    public double accuracyPercent() {
        return totalItems == 0 ? 0.0 : correctCount * 100.0 / totalItems;
    }
}
