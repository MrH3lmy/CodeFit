package com.codefit.service;

import com.codefit.model.ReviewRating;

import java.util.EnumMap;
import java.util.Map;

public record StatsSkillPerformance(
        String skillCategory,
        int totalCards,
        int dueCards,
        int recentReviews,
        int correctCount,
        int againCount,
        int hardCount,
        int goodCount,
        int easyCount
) {
    public double accuracyPercent() {
        return recentReviews == 0 ? 0.0 : correctCount * 100.0 / recentReviews;
    }

    public double needsPracticeRate() {
        return recentReviews == 0 ? 0.0 : (againCount + hardCount) * 100.0 / recentReviews;
    }

    public boolean needsPractice() {
        return recentReviews >= 2 && needsPracticeRate() >= 40.0;
    }

    public Map<ReviewRating, Integer> ratingDistribution() {
        Map<ReviewRating, Integer> distribution = new EnumMap<>(ReviewRating.class);
        distribution.put(ReviewRating.AGAIN, againCount);
        distribution.put(ReviewRating.HARD, hardCount);
        distribution.put(ReviewRating.GOOD, goodCount);
        distribution.put(ReviewRating.EASY, easyCount);
        return distribution;
    }
}
