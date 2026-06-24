package com.codefit.service;

public record EngineerReadinessStats(
        int recentReviewCount,
        double readinessScore,
        double recentAccuracyPercent,
        double timedSuccessPercent,
        double weakAreaRatePercent,
        double consistencyPercent
) {
    public boolean hasSignal() {
        return recentReviewCount > 0;
    }
}
