package com.codefit.service;

import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReviewService {
    private static final int WEEKLY_BOSS_CARD_LIMIT = 12;
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();
    private final SpacedRepetitionService spacedRepetitionService = new SpacedRepetitionService();
    private final ProgressService progressService = new ProgressService();
    private final DailyQuestService dailyQuestService = new DailyQuestService();

    public DailyWorkloadMode getDailyWorkloadMode() {
        return userProgressRepository.getProgress().getDailyWorkloadMode();
    }

    public List<Flashcard> getDueCards() {
        DailyWorkloadMode mode = getDailyWorkloadMode();
        return flashcardRepository.findDueCards().stream()
                .limit(mode.getReviewSessionLimit())
                .toList();
    }

    public List<Flashcard> getWeeklyBossCards() {
        LocalDate today = LocalDate.now();
        Map<Long, CardPressure> pressureByCardId = new HashMap<>();
        reviewHistoryRepository.findRecent(200).forEach(history -> {
            CardPressure pressure = pressureByCardId.computeIfAbsent(history.getFlashcardId(), ignored -> new CardPressure());
            pressure.reviewCount++;
            if (history.getRating() == ReviewRating.AGAIN) {
                pressure.againCount++;
            } else if (history.getRating() == ReviewRating.HARD) {
                pressure.hardCount++;
            } else if (history.getRating() == ReviewRating.GOOD || history.getRating() == ReviewRating.EASY) {
                pressure.successCount++;
            }
        });

        List<Flashcard> prioritizedCards = flashcardRepository.findAll().stream()
                .sorted(Comparator.comparingDouble((Flashcard card) -> -weeklyBossPriority(card, pressureByCardId.get(card.getId()), today))
                        .thenComparing(Flashcard::getSkillCategory, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparingLong(Flashcard::getDeckId)
                        .thenComparing(Flashcard::getDueDate))
                .toList();
        return mixedWeeklyBossCards(prioritizedCards);
    }

    public boolean isWeeklyBossAvailable() {
        return !getWeeklyBossCards().isEmpty()
                && !reviewHistoryRepository.hasBossBattleSince(LocalDate.now().minusDays(6));
    }

    public void reviewBossBattle(Flashcard card, ReviewRating rating, boolean submittedInTime) {
        recordReview(card, rating, submittedInTime, true);
    }

    public void review(Flashcard card, ReviewRating rating) {
        review(card, rating, true);
    }

    public void review(Flashcard card, ReviewRating rating, boolean submittedInTime) {
        recordReview(card, rating, submittedInTime, false);
    }

    private void recordReview(Flashcard card, ReviewRating rating, boolean submittedInTime, boolean bossBattle) {
        int previousInterval = card.getIntervalDays();
        spacedRepetitionService.applyReview(card, rating);
        flashcardRepository.updateSchedule(card);
        reviewHistoryRepository.save(new ReviewHistory(0, card.getId(), rating, previousInterval,
                card.getIntervalDays(), java.time.LocalDateTime.now(), submittedInTime, bossBattle));
        if (!bossBattle) {
            progressService.recordReview(rating);
            dailyQuestService.recordReview(card);
        }
    }

    private List<Flashcard> mixedWeeklyBossCards(List<Flashcard> prioritizedCards) {
        Map<String, List<Flashcard>> cardsByArea = new LinkedHashMap<>();
        prioritizedCards.forEach(card -> cardsByArea
                .computeIfAbsent(normalizeBossArea(card), ignored -> new ArrayList<>())
                .add(card));
        List<Flashcard> mixedCards = new ArrayList<>();
        int round = 0;
        while (mixedCards.size() < WEEKLY_BOSS_CARD_LIMIT) {
            boolean addedCard = false;
            for (List<Flashcard> areaCards : cardsByArea.values()) {
                if (round < areaCards.size() && mixedCards.size() < WEEKLY_BOSS_CARD_LIMIT) {
                    mixedCards.add(areaCards.get(round));
                    addedCard = true;
                }
            }
            if (!addedCard) {
                break;
            }
            round++;
        }
        return mixedCards;
    }

    private String normalizeBossArea(Flashcard card) {
        String skill = card.getSkillCategory() == null || card.getSkillCategory().isBlank() ? "General" : card.getSkillCategory().strip();
        return card.getDeckId() + "::" + skill;
    }

    private double weeklyBossPriority(Flashcard card, CardPressure pressure, LocalDate today) {
        double score = 0.0;
        if (card.getDueDate() != null && !card.getDueDate().isAfter(today)) {
            score += 30.0 + Math.min(20.0, java.time.temporal.ChronoUnit.DAYS.between(card.getDueDate(), today));
        }
        if (pressure != null) {
            score += (pressure.againCount * 10.0) + (pressure.hardCount * 6.0);
            score += pressure.reviewCount == 0 ? 5.0 : (1.0 - (pressure.successCount / (double) pressure.reviewCount)) * 25.0;
        } else {
            score += 8.0;
        }
        return score;
    }

    private static final class CardPressure {
        private int reviewCount;
        private int againCount;
        private int hardCount;
        private int successCount;
    }
}
