package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReviewService {
    private static final int WEEKLY_BOSS_CARD_LIMIT = 12;
    public static final int DEFAULT_DAILY_NEW_CARD_LIMIT = 2;

    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();
    private final SpacedRepetitionService spacedRepetitionService = new SpacedRepetitionService();
    private final ProgressService progressService = new ProgressService();
    private final DailyQuestService dailyQuestService = new DailyQuestService();
    private final MasteryService masteryService = new MasteryService();
    private final CardLifecycleService cardLifecycleService = new CardLifecycleService();
    private final SessionBudgetService sessionBudgetService = new SessionBudgetService();
    private final StatsService statsService = new StatsService();
    private final int dailyNewCardLimit;

    public ReviewService() {
        this(DEFAULT_DAILY_NEW_CARD_LIMIT);
    }

    public ReviewService(int dailyNewCardLimit) {
        this.dailyNewCardLimit = dailyNewCardLimit;
    }

    public DailyWorkloadMode getDailyWorkloadMode() {
        return userProgressRepository.getProgress().getDailyWorkloadMode();
    }

    /**
     * Due and relearning cards always take precedence; new cards (capped by the daily new-card
     * limit and mixed across decks/modules rather than creation order) only fill remaining room
     * in the workload mode's session size.
     */
    public List<Flashcard> getDueCards() {
        DailyWorkloadMode mode = getDailyWorkloadMode();
        List<Flashcard> dueAndRelearning = flashcardRepository.findDueCards();
        int remainingNewCardBudget = dailyNewCardLimit - flashcardRepository.countIntroducedToday();
        List<Flashcard> newCards = selectNewCardsMixedAcrossDecks(Math.max(0, remainingNewCardBudget));

        List<Flashcard> combined = new ArrayList<>(dueAndRelearning);
        combined.addAll(newCards);
        return combined.stream().limit(mode.getReviewSessionLimit()).toList();
    }

    private List<Flashcard> selectNewCardsMixedAcrossDecks(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<Long, Deque<Flashcard>> byDeck = new LinkedHashMap<>();
        for (Flashcard card : flashcardRepository.findNewCards()) {
            byDeck.computeIfAbsent(card.getDeckId(), ignored -> new ArrayDeque<>()).add(card);
        }

        List<Flashcard> selected = new ArrayList<>();
        boolean addedAny = true;
        while (selected.size() < limit && addedAny) {
            addedAny = false;
            for (Deque<Flashcard> deckQueue : byDeck.values()) {
                if (selected.size() >= limit) {
                    break;
                }
                Flashcard next = deckQueue.poll();
                if (next != null) {
                    selected.add(next);
                    addedAny = true;
                }
            }
        }
        return selected;
    }

    /** How many new cards can still be introduced today, respecting the daily new-card limit. */
    public int getAvailableNewCardBudget() {
        int remaining = dailyNewCardLimit - flashcardRepository.countIntroducedToday();
        return Math.max(0, Math.min(remaining, flashcardRepository.countNew()));
    }

    /**
     * Builds a time-budgeted session: due/relearning, weakest-skill, and recently-failed cards
     * are mixed ahead of new/stretch cards (60/20/10/10 target ratio) so due work is never
     * displaced by optional new content, then trimmed to fit the chosen time budget using each
     * card's own historical response time.
     */
    public AdaptiveSessionPlan getAdaptiveSessionCards(int budgetMinutes) {
        List<Flashcard> dueAndRelearning = flashcardRepository.findDueCards();

        List<Flashcard> forgettingRiskOrder = dueAndRelearning.stream()
                .sorted(Comparator.comparing(Flashcard::getDueDate).thenComparingDouble(Flashcard::getEaseFactor))
                .toList();

        List<String> weakSkills = statsService.getNeedsPracticeSkills().stream()
                .map(StatsSkillPerformance::skillCategory)
                .toList();
        List<Flashcard> weakestSkillOrder = dueAndRelearning.stream()
                .filter(card -> weakSkills.contains(normalizeSkillCategory(card.getSkillCategory())))
                .toList();

        List<Flashcard> recentlyFailedOrder = dueAndRelearning.stream()
                .filter(card -> card.getCardState() == CardState.RELEARNING)
                .toList();

        List<Flashcard> newCardOrder = selectNewCardsMixedAcrossDecks(getAvailableNewCardBudget());

        List<AdaptiveQueueMixer.Bucket> buckets = List.of(
                new AdaptiveQueueMixer.Bucket("Highest forgetting risk", forgettingRiskOrder, 0.6),
                new AdaptiveQueueMixer.Bucket("Weakest skill", weakestSkillOrder, 0.2),
                new AdaptiveQueueMixer.Bucket("Recently failed", recentlyFailedOrder, 0.1),
                new AdaptiveQueueMixer.Bucket("New / stretch", newCardOrder, 0.1)
        );
        int candidatePoolCap = dueAndRelearning.size() + newCardOrder.size();
        List<AdaptiveQueueMixer.MixedCard> mixed = new AdaptiveQueueMixer().mix(buckets, candidatePoolCap);

        Map<Long, String> bucketLabelByCardId = new HashMap<>();
        List<Flashcard> mixedCards = new ArrayList<>();
        for (AdaptiveQueueMixer.MixedCard entry : mixed) {
            bucketLabelByCardId.put(entry.card().getId(), entry.bucketLabel());
            mixedCards.add(entry.card());
        }

        List<Flashcard> selected = sessionBudgetService.selectWithinBudget(mixedCards, budgetMinutes);
        Map<String, Integer> composition = new LinkedHashMap<>();
        for (Flashcard card : selected) {
            composition.merge(bucketLabelByCardId.getOrDefault(card.getId(), "Other"), 1, Integer::sum);
        }
        int estimatedSeconds = sessionBudgetService.estimateTotalSeconds(selected);
        return new AdaptiveSessionPlan(selected, composition, estimatedSeconds);
    }

    private String normalizeSkillCategory(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }

    public record AdaptiveSessionPlan(List<Flashcard> cards, Map<String, Integer> composition, int estimatedSeconds) {
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
                .filter(card -> card.getCardState() != CardState.SUSPENDED)
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

    public void reviewBossBattle(Flashcard card, ReviewRating rating, boolean submittedInTime, ReviewAttempt attempt) {
        recordReview(card, rating, submittedInTime, true, attempt);
    }

    public void review(Flashcard card, ReviewRating rating, boolean submittedInTime, ReviewAttempt attempt) {
        recordReview(card, rating, submittedInTime, false, attempt);
    }

    private void recordReview(Flashcard card, ReviewRating rating, boolean submittedInTime, boolean bossBattle,
                              ReviewAttempt attempt) {
        int previousInterval = card.getIntervalDays();
        spacedRepetitionService.applyReview(card, rating);
        reviewHistoryRepository.save(new ReviewHistory(0, card.getId(), rating, previousInterval,
                card.getIntervalDays(), java.time.LocalDateTime.now(), submittedInTime, bossBattle,
                attempt.validationResult(), attempt.submittedAnswer(), attempt.responseTimeMs(),
                attempt.hintUsed(), attempt.sessionId()));
        // Evaluated after saving history so this review counts toward the mastery decision.
        MasteryService.CardMasteryState masteryState = masteryService.getMasteryState(card);
        cardLifecycleService.applyReviewOutcome(card, rating, masteryState);
        flashcardRepository.updateSchedule(card);
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
