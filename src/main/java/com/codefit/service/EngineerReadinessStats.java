package com.codefit.service;

public record EngineerReadinessStats(
        int recentReviewCount,
        double readinessScore,
        double recentAccuracyPercent,
        double timedSuccessPercent,
        double weakAreaRatePercent,
        double consistencyPercent,
        double subjectiveSelfAssessmentPercent,
        double confidenceCalibrationPercent,
        int confidenceSampleCount
) {
    public boolean hasSignal() {
        return recentReviewCount > 0;
    }

    public boolean hasConfidenceSignal() {
        return confidenceSampleCount > 0;
    }
}
