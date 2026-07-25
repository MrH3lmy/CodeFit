package com.codefit.service;

/**
 * Transfer-assessment accuracy for one skill/concept, kept as its own reporting type (distinct from
 * {@link StatsSkillPerformance}) so it is never confused with normal-review skill performance in
 * the UI or in code (#104).
 */
public record TransferSkillPerformance(String skillCategory, int attempts, int correctCount) {
    public double accuracyPercent() {
        return attempts == 0 ? 0.0 : correctCount * 100.0 / attempts;
    }
}
