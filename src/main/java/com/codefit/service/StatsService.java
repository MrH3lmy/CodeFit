package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class StatsService {
    private static final int RECENT_SKILL_REVIEW_LIMIT = 100;
    private static final int READINESS_REVIEW_LIMIT = 50;
    private static final int CONSISTENCY_WINDOW_DAYS = 7;

    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ProgressService progressService = new ProgressService();

    public UserProgress getProgress() {
        return progressService.getProgress();
    }

    public int getTotalCards() {
        return flashcardRepository.countAll();
    }

    public int getDueCards() {
        return flashcardRepository.countDue();
    }

    public int getReviewedToday() {
        return reviewHistoryRepository.countReviewedToday();
    }

    public List<ReviewHistory> getRecentReviews() {
        return reviewHistoryRepository.findRecent(10);
    }

    public WeeklyBossResult getLatestWeeklyBossResult() {
        List<ReviewHistory> bossReviews = reviewHistoryRepository.findRecentBossBattles(50);
        if (bossReviews.isEmpty()) {
            return WeeklyBossResult.empty();
        }
        LocalDate latestDate = bossReviews.getFirst().getReviewedAt().toLocalDate();
        List<ReviewHistory> latestSession = bossReviews.stream()
                .filter(history -> history.getReviewedAt().toLocalDate().equals(latestDate))
                .toList();
        double score = getOverallRecentAccuracy(latestSession);
        Map<Long, Flashcard> cardsById = flashcardRepository.findAll().stream()
                .collect(Collectors.toMap(Flashcard::getId, card -> card));
        Map<String, SkillAccumulator> bySkill = new HashMap<>();
        latestSession.forEach(history -> {
            Flashcard card = cardsById.get(history.getFlashcardId());
            String skill = card == null ? "Deleted cards" : normalizeSkill(card.getSkillCategory());
            bySkill.computeIfAbsent(skill, SkillAccumulator::new).record(history.getRating());
        });
        List<String> weakAreas = bySkill.values().stream()
                .map(SkillAccumulator::toPerformance)
                .filter(performance -> performance.needsPracticeRate() > 0)
                .sorted(Comparator.comparingDouble(StatsSkillPerformance::needsPracticeRate).reversed())
                .limit(3)
                .map(StatsSkillPerformance::skillCategory)
                .toList();
        String focus = weakAreas.isEmpty()
                ? "Maintain strength with mixed timed practice and one stretch card."
                : "Prioritize " + String.join(", ", weakAreas) + " with due-card drills and new targeted prompts.";
        return new WeeklyBossResult(true, latestSession.size(), score, weakAreas, focus);
    }

    public boolean isWeeklyBossAvailable() {
        return new ReviewService().isWeeklyBossAvailable();
    }

    public EngineerReadinessStats getEngineerReadinessStats() {
        List<ReviewHistory> recentReviews = reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT);
        double recentAccuracy = getOverallRecentAccuracy(recentReviews);
        double timedSuccessRate = getTimedSuccessRate(recentReviews);
        double weakAreaRate = getWeakAreaRate(recentReviews);
        double consistencyScore = getConsistencyScore(recentReviews);
        double readinessScore = recentReviews.isEmpty() ? 0.0
                : (recentAccuracy * 0.40) + (timedSuccessRate * 0.25)
                + ((100.0 - weakAreaRate) * 0.20) + (consistencyScore * 0.15);

        return new EngineerReadinessStats(recentReviews.size(), readinessScore, recentAccuracy,
                timedSuccessRate, weakAreaRate, consistencyScore);
    }

    public double getOverallRecentAccuracy() {
        return getOverallRecentAccuracy(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getTimedSuccessRate() {
        return getTimedSuccessRate(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getWeakAreaRate() {
        return getWeakAreaRate(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getConsistencyScore() {
        return getConsistencyScore(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    private double getOverallRecentAccuracy(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }
        long successfulReviews = recentReviews.stream()
                .filter(history -> history.getRating() == ReviewRating.GOOD || history.getRating() == ReviewRating.EASY)
                .count();
        return successfulReviews * 100.0 / recentReviews.size();
    }

    private double getTimedSuccessRate(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }
        long submittedInTime = recentReviews.stream()
                .filter(ReviewHistory::isSubmittedInTime)
                .count();
        return submittedInTime * 100.0 / recentReviews.size();
    }

    private double getWeakAreaRate(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }
        long weakAreaReviews = recentReviews.stream()
                .filter(history -> history.getRating() == ReviewRating.AGAIN || history.getRating() == ReviewRating.HARD)
                .count();
        return weakAreaReviews * 100.0 / recentReviews.size();
    }

    private double getConsistencyScore(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }

        LocalDate today = LocalDate.now();
        Set<LocalDate> activeReviewDates = recentReviews.stream()
                .map(ReviewHistory::getReviewedAt)
                .map(reviewedAt -> reviewedAt == null ? today : reviewedAt.toLocalDate())
                .filter(reviewDate -> !reviewDate.isAfter(today))
                .filter(reviewDate -> ChronoUnit.DAYS.between(reviewDate, today) < CONSISTENCY_WINDOW_DAYS)
                .collect(Collectors.toSet());

        return activeReviewDates.size() * 100.0 / CONSISTENCY_WINDOW_DAYS;
    }

    public List<StatsSkillPerformance> getSkillPerformance() {
        Map<Long, Flashcard> cardsById = flashcardRepository.findAll().stream()
                .collect(Collectors.toMap(Flashcard::getId, card -> card));
        Map<String, SkillAccumulator> bySkill = new HashMap<>();
        LocalDate today = LocalDate.now();

        cardsById.values().forEach(card -> {
            SkillAccumulator accumulator = bySkill.computeIfAbsent(normalizeSkill(card.getSkillCategory()), SkillAccumulator::new);
            accumulator.totalCards++;
            if (card.getDueDate() != null && !card.getDueDate().isAfter(today)) {
                accumulator.dueCards++;
            }
        });

        reviewHistoryRepository.findRecent(RECENT_SKILL_REVIEW_LIMIT).forEach(history -> {
            Flashcard card = cardsById.get(history.getFlashcardId());
            String skill = card == null ? "Deleted cards" : normalizeSkill(card.getSkillCategory());
            bySkill.computeIfAbsent(skill, SkillAccumulator::new).record(history.getRating());
        });

        return bySkill.values().stream()
                .map(SkillAccumulator::toPerformance)
                .sorted(Comparator.comparing(StatsSkillPerformance::needsPractice).reversed()
                        .thenComparing(StatsSkillPerformance::accuracyPercent)
                        .thenComparing(StatsSkillPerformance::skillCategory))
                .toList();
    }

    public List<StatsSkillPerformance> getNeedsPracticeSkills() {
        return getSkillPerformance().stream()
                .filter(StatsSkillPerformance::needsPractice)
                .toList();
    }

    private String normalizeSkill(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }

    private static final class SkillAccumulator {
        private final String skill;
        private int totalCards;
        private int dueCards;
        private int againCount;
        private int hardCount;
        private int goodCount;
        private int easyCount;

        private SkillAccumulator(String skill) {
            this.skill = skill;
        }

        private void record(ReviewRating rating) {
            if (rating == null) {
                return;
            }
            switch (rating) {
                case AGAIN -> againCount++;
                case HARD -> hardCount++;
                case GOOD -> goodCount++;
                case EASY -> easyCount++;
            }
        }

        private StatsSkillPerformance toPerformance() {
            return new StatsSkillPerformance(skill, totalCards, dueCards,
                    againCount + hardCount + goodCount + easyCount,
                    againCount, hardCount, goodCount, easyCount);
        }
    }
}
