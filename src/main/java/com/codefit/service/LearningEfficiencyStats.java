package com.codefit.service;

import com.codefit.model.CardType;

import java.util.Map;

/**
 * Whether training time is producing durable knowledge, as opposed to XP/streaks/review counts
 * which mainly reflect activity. Presented as its own dashboard section, separate from
 * gamification and readiness metrics. See {@link StatsService#getLearningEfficiencyStats()} for
 * how each figure is derived and {@link MasteryService} for the underlying mastery definition.
 */
public record LearningEfficiencyStats(
        int reviewCount,
        double activeReviewHours,
        int masteredCardsInScope,
        double masteredCardsPerHour,
        int objectiveRecallCount,
        double objectiveRecallsPerMinute,
        int sessionCount,
        int recoveredMisses,
        double recoveredMissesPerSession,
        RetentionByInterval retentionByInterval,
        Map<String, Double> activeMinutesBySkill,
        Map<CardType, Double> activeMinutesByCardType,
        int suspendedCardCount,
        double suspendedCardActiveMinutes,
        double confidenceCalibrationPercent,
        int confidenceSampleCount
) {
    /**
     * Minimum active review time before rate-based metrics (mastered/hr, recalls/min) are shown
     * instead of an insufficient-data label; a few seconds of response time would otherwise blow
     * up into an implausible per-hour rate. Matches the threshold the dashboard already used for
     * its mastered-per-hour figure before this section existed.
     */
    public static final double MIN_ACTIVE_HOURS_FOR_RATE_SIGNAL = 0.05;

    public boolean hasReviewSignal() {
        return reviewCount > 0;
    }

    public boolean hasTrainingTimeSignal() {
        return activeReviewHours >= MIN_ACTIVE_HOURS_FOR_RATE_SIGNAL;
    }

    public boolean hasSessionSignal() {
        return sessionCount > 0;
    }

    public boolean hasConfidenceSignal() {
        return confidenceSampleCount > 0;
    }

    /** Zero suspended cards is a legitimate, non-misleading state (not "insufficient data"). */
    public boolean hasSuspendedCardSignal() {
        return suspendedCardCount > 0;
    }

    /**
     * Retention checked at the 7/14/30+ day spacing intervals the issue calls for. Each bucket
     * groups reviews by the gap since the card's previous attempt (previousIntervalDays), because
     * that gap is what "retention after N days" is actually measuring.
     */
    public record RetentionByInterval(RetentionBucket sevenToThirteenDays, RetentionBucket fourteenToTwentyNineDays,
                                       RetentionBucket thirtyPlusDays) {
    }

    public record RetentionBucket(int sampleSize, int retainedCount) {
        public double retentionPercent() {
            return sampleSize == 0 ? 0.0 : retainedCount * 100.0 / sampleSize;
        }

        public boolean hasSignal() {
            return sampleSize > 0;
        }
    }
}
