package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
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
import java.util.Set;
import java.util.function.ToIntFunction;

public class ReviewService {
    private static final int WEEKLY_BOSS_CARD_LIMIT = 12;
    public static final int DEFAULT_DAILY_NEW_CARD_LIMIT = 2;
    /** How many recent (non-boss-battle) reviews are fetched as leech-detection evidence; matches
     *  MasteryService's own lookback so both decisions look at a comparably-sized recent window. */
    private static final int LEECH_EVIDENCE_LOOKBACK = 10;

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
    private final FocusPreferenceService focusPreferenceService = new FocusPreferenceService();
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
        return selectNewCardsMixedAcrossDecks(flashcardRepository.findNewCards(), limit);
    }

    /**
     * New/stretch cards should come mainly from the learner's chosen focus module (#110): drain the
     * focus module's own new cards first (still mixed across its decks if it spans more than one),
     * then only fall back to the existing cross-deck mix for any remaining budget. When no focus
     * module is set this degrades to the original unbiased mix.
     */
    private List<Flashcard> selectNewCardsFavoringFocus(int limit, Set<Long> focusDeckIds) {
        return selectNewCardsFavoringFocus(flashcardRepository.findNewCards(), limit, focusDeckIds);
    }

    static List<Flashcard> selectNewCardsFavoringFocus(List<Flashcard> candidates, int limit, Set<Long> focusDeckIds) {
        if (limit <= 0) {
            return List.of();
        }
        if (focusDeckIds == null || focusDeckIds.isEmpty()) {
            return selectNewCardsMixedAcrossDecks(candidates, limit);
        }

        List<Flashcard> focusCandidates = candidates.stream().filter(card -> focusDeckIds.contains(card.getDeckId())).toList();
        List<Flashcard> otherCandidates = candidates.stream().filter(card -> !focusDeckIds.contains(card.getDeckId())).toList();

        List<Flashcard> selected = new ArrayList<>(selectNewCardsMixedAcrossDecks(focusCandidates, limit));
        if (selected.size() < limit) {
            selected.addAll(selectNewCardsMixedAcrossDecks(otherCandidates, limit - selected.size()));
        }
        return selected;
    }

    /** Package-private/static so the daily-limit-respecting selection is directly unit testable without a database. */
    static List<Flashcard> selectNewCardsMixedAcrossDecks(List<Flashcard> candidates, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<Long, Deque<Flashcard>> byDeck = new LinkedHashMap<>();
        for (Flashcard card : candidates) {
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
        return computeAvailableNewCardBudget(dailyNewCardLimit, flashcardRepository.countIntroducedToday(),
                flashcardRepository.countNew());
    }

    static int computeAvailableNewCardBudget(int dailyNewCardLimit, int introducedToday, int totalNewCards) {
        int remaining = dailyNewCardLimit - introducedToday;
        return Math.max(0, Math.min(remaining, totalNewCards));
    }

    /**
     * Builds a time-budgeted session. Due and relearning cards are never displaced by optional
     * new content, and are never filtered by focus module: the full time budget is offered to the
     * due/relearning queue (relearning first, then weakest-skill due cards, then the rest ordered
     * by forgetting risk) from every module before any new/stretch card is even considered. New
     * cards only fill genuinely leftover budget, favoring the learner's focus module (#110); a
     * small configurable share of what's left after that is reserved for mature-card interleaving
     * from other modules so older material doesn't rot.
     */
    public AdaptiveSessionPlan getAdaptiveSessionCards(int budgetMinutes) {
        List<Flashcard> dueAndRelearning = flashcardRepository.findDueCards();
        List<String> weakSkills = statsService.getNeedsPracticeSkills().stream()
                .map(StatsSkillPerformance::skillCategory)
                .toList();
        UserProgress progress = userProgressRepository.getProgress();
        Set<Long> focusDeckIds = focusPreferenceService.getFocusDeckIds(progress);
        List<Flashcard> newCardOrder = selectNewCardsFavoringFocus(getAvailableNewCardBudget(), focusDeckIds);
        List<Flashcard> matureInterleaveOrder = selectMatureInterleaveCandidates(focusDeckIds);

        return buildAdaptiveSessionPlan(dueAndRelearning, weakSkills, newCardOrder, matureInterleaveOrder,
                progress.getMatureInterleavePercent(), budgetMinutes, sessionBudgetService::estimateCardSeconds);
    }

    /** Mature (settled-interval), not-yet-due cards from modules other than the focus module, mixed for variety. */
    private List<Flashcard> selectMatureInterleaveCandidates(Set<Long> focusDeckIds) {
        if (focusDeckIds.isEmpty()) {
            return List.of();
        }
        List<Flashcard> matureFromOtherModules = flashcardRepository
                .findMatureNotDueCards(MasteryService.DEFAULT_THRESHOLDS.minIntervalDays()).stream()
                .filter(card -> !focusDeckIds.contains(card.getDeckId()))
                .toList();
        return selectNewCardsMixedAcrossDecks(matureFromOtherModules, matureFromOtherModules.size());
    }

    static AdaptiveSessionPlan buildAdaptiveSessionPlan(List<Flashcard> dueAndRelearning, List<String> weakSkills,
                                                        List<Flashcard> newCardOrder, int budgetMinutes,
                                                        ToIntFunction<Flashcard> estimator) {
        return buildAdaptiveSessionPlan(dueAndRelearning, weakSkills, newCardOrder, List.of(), 0, budgetMinutes, estimator);
    }

    /**
     * @param matureInterleaveOrder  candidate mature cards from non-focus modules, pre-mixed for
     *                               variety; only ever fills leftover budget, never displacing due
     *                               cards or the focus module's new cards
     * @param matureInterleavePercent share (0-100) of leftover (post-due) budget reserved for
     *                                matureInterleaveOrder before new cards get the rest (#110)
     */
    static AdaptiveSessionPlan buildAdaptiveSessionPlan(List<Flashcard> dueAndRelearning, List<String> weakSkills,
                                                        List<Flashcard> newCardOrder, List<Flashcard> matureInterleaveOrder,
                                                        int matureInterleavePercent, int budgetMinutes,
                                                        ToIntFunction<Flashcard> estimator) {
        int budgetSeconds = Math.max(0, budgetMinutes) * 60;
        List<Flashcard> duePriorityOrder = orderDueQueue(dueAndRelearning, weakSkills);

        List<Flashcard> selectedDue = SessionBudgetService.selectWithinBudgetSeconds(duePriorityOrder, budgetSeconds, estimator);
        int usedSeconds = selectedDue.stream().mapToInt(estimator::applyAsInt).sum();
        int remainingSeconds = Math.max(0, budgetSeconds - usedSeconds);

        // When there are due cards, selectedDue already guarantees the session isn't empty, so
        // leftover budget must only go to optional content that genuinely fits — never force one
        // in. When there are no due cards at all, new cards alone carry the "never start empty"
        // guarantee and interleaving (a nice-to-have) is skipped.
        List<Flashcard> selectedInterleave;
        List<Flashcard> selectedNew;
        if (selectedDue.isEmpty()) {
            selectedNew = SessionBudgetService.selectWithinBudgetSeconds(newCardOrder, remainingSeconds, estimator);
            selectedInterleave = List.of();
        } else if (remainingSeconds > 0) {
            int interleaveBudgetSeconds = remainingSeconds * clampPercent(matureInterleavePercent) / 100;
            selectedInterleave = SessionBudgetService.selectFittingWithinBudgetSeconds(matureInterleaveOrder, interleaveBudgetSeconds, estimator);
            int interleaveUsedSeconds = selectedInterleave.stream().mapToInt(estimator::applyAsInt).sum();
            selectedNew = SessionBudgetService.selectFittingWithinBudgetSeconds(newCardOrder, remainingSeconds - interleaveUsedSeconds, estimator);
        } else {
            selectedNew = List.of();
            selectedInterleave = List.of();
        }

        Map<String, Integer> composition = new LinkedHashMap<>();
        for (Flashcard card : selectedDue) {
            composition.merge(classifyDueCard(card, weakSkills), 1, Integer::sum);
        }
        if (!selectedInterleave.isEmpty()) {
            composition.merge("Interleaved review (other modules)", selectedInterleave.size(), Integer::sum);
        }
        if (!selectedNew.isEmpty()) {
            composition.merge("New / stretch", selectedNew.size(), Integer::sum);
        }

        List<Flashcard> allSelected = new ArrayList<>(selectedDue);
        allSelected.addAll(selectedInterleave);
        allSelected.addAll(selectedNew);
        int estimatedSeconds = allSelected.stream().mapToInt(estimator::applyAsInt).sum();
        return new AdaptiveSessionPlan(allSelected, composition, estimatedSeconds);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /** Relearning cards are highest priority, then due cards in weak skills, then the rest by forgetting risk (most overdue, lowest ease first). */
    private static List<Flashcard> orderDueQueue(List<Flashcard> dueAndRelearning, List<String> weakSkills) {
        List<Flashcard> relearning = dueAndRelearning.stream()
                .filter(card -> card.getCardState() == CardState.RELEARNING)
                .sorted(Comparator.comparing(Flashcard::getDueDate))
                .toList();
        List<Flashcard> rest = dueAndRelearning.stream()
                .filter(card -> card.getCardState() != CardState.RELEARNING)
                .sorted(Comparator
                        .comparing((Flashcard card) -> !weakSkills.contains(normalizeSkillCategory(card.getSkillCategory())))
                        .thenComparing(Flashcard::getDueDate)
                        .thenComparingDouble(Flashcard::getEaseFactor))
                .toList();
        List<Flashcard> ordered = new ArrayList<>(relearning);
        ordered.addAll(rest);
        return ordered;
    }

    /**
     * LEECH is deliberately not treated as "Recently failed" or "Weakest skill" here: those labels
     * feed the same priority ordering that keeps requeuing a card every session, which is exactly
     * what the issue asks a flagged leech to avoid. It's called out under its own label instead, and
     * otherwise falls into the normal due-date/ease ordering in {@link #orderDueQueue} rather than
     * jumping the queue.
     */
    private static String classifyDueCard(Flashcard card, List<String> weakSkills) {
        if (card.getCardState() == CardState.RELEARNING) {
            return "Recently failed";
        }
        if (card.getCardState() == CardState.LEECH) {
            return "Needs rewrite";
        }
        if (weakSkills.contains(normalizeSkillCategory(card.getSkillCategory()))) {
            return "Weakest skill";
        }
        return "Highest forgetting risk";
    }

    private static String normalizeSkillCategory(String skillCategory) {
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

        List<Flashcard> prioritizedCards = prioritizeWeeklyBossCandidates(flashcardRepository.findAll(), pressureByCardId, today);
        return mixedWeeklyBossCards(prioritizedCards);
    }

    /** Package-private/static so the suspended-card exclusion is directly unit testable without a database. */
    static List<Flashcard> prioritizeWeeklyBossCandidates(List<Flashcard> allCards, Map<Long, CardPressure> pressureByCardId, LocalDate today) {
        return allCards.stream()
                // A leech is actively being worked on/rewritten, not a fair candidate for a
                // high-pressure timed assessment.
                .filter(card -> card.getCardState() != CardState.SUSPENDED && card.getCardState() != CardState.LEECH)
                .sorted(Comparator.comparingDouble((Flashcard card) -> -weeklyBossPriority(card, pressureByCardId.get(card.getId()), today))
                        .thenComparing(Flashcard::getSkillCategory, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparingLong(Flashcard::getDeckId)
                        .thenComparing(Flashcard::getDueDate))
                .toList();
    }

    /**
     * Diagnostic graduation for a new card the learner already knows; see
     * {@link CardLifecycleService#graduate}. Callers must gate this on
     * {@link RatingGuardrail#canGraduate} first — this method trusts the caller and does not
     * re-check correctness/hint/timing itself.
     */
    public void graduateCard(Flashcard card) {
        graduateCard(card, CardLifecycleService.DEFAULT_GRADUATION_INTERVAL_DAYS);
    }

    public void graduateCard(Flashcard card, int intervalDays) {
        cardLifecycleService.graduate(card, intervalDays);
        flashcardRepository.updateSchedule(card);
    }

    /** Removes a card from every review queue (normal, adaptive, and weekly boss) until reactivated. */
    public void suspendCard(Flashcard card) {
        cardLifecycleService.suspend(card);
        flashcardRepository.updateSchedule(card);
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
                attempt.hintUsed(), attempt.sessionId(),
                attempt.confidence() == null ? null : attempt.confidence().name()));
        // Evaluated after saving history so this review counts toward the mastery and leech decisions.
        MasteryService.CardMasteryState masteryState = masteryService.getMasteryState(card);
        List<ReviewHistory> recentHistory = reviewHistoryRepository.findRecentForFlashcard(card.getId(), LEECH_EVIDENCE_LOOKBACK);
        cardLifecycleService.applyReviewOutcome(card, rating, masteryState, recentHistory);
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

    private static double weeklyBossPriority(Flashcard card, CardPressure pressure, LocalDate today) {
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

    static final class CardPressure {
        int reviewCount;
        int againCount;
        int hardCount;
        int successCount;
    }
}
