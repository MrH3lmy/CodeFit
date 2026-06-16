package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StatsService {
    private static final int RECENT_SKILL_REVIEW_LIMIT = 100;

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
