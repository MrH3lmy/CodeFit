package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.repository.ReviewHistoryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Estimates how long a card takes to review (from its own recent response times, falling back to
 * a default) and selects the cards from an already-prioritized queue whose estimated total time
 * fits a learner's chosen time budget.
 */
public class SessionBudgetService {
    public static final int QUICK_MINUTES = 5;
    public static final int STANDARD_MINUTES = 15;
    public static final int DEEP_MINUTES = 30;

    static final int DEFAULT_RESPONSE_SECONDS = 15;
    static final int REVEAL_AND_RATE_OVERHEAD_SECONDS = 5;
    private static final int RESPONSE_TIME_HISTORY_LOOKBACK = 5;

    private final ReviewHistoryRepository reviewHistoryRepository;

    public SessionBudgetService() {
        this(new ReviewHistoryRepository());
    }

    public SessionBudgetService(ReviewHistoryRepository reviewHistoryRepository) {
        this.reviewHistoryRepository = reviewHistoryRepository;
    }

    public int estimateCardSeconds(Flashcard card) {
        return estimateCardSeconds(reviewHistoryRepository.findRecentForFlashcard(card.getId(), RESPONSE_TIME_HISTORY_LOOKBACK));
    }

    public int estimateTotalSeconds(List<Flashcard> cards) {
        return cards.stream().mapToInt(this::estimateCardSeconds).sum();
    }

    public List<Flashcard> selectWithinBudget(List<Flashcard> prioritizedCards, int budgetMinutes) {
        return selectWithinBudget(prioritizedCards, budgetMinutes, this::estimateCardSeconds);
    }

    /** Same as {@link #selectWithinBudget(List, int)} but the budget is given directly in seconds. */
    public List<Flashcard> selectWithinBudgetSeconds(List<Flashcard> prioritizedCards, int budgetSeconds) {
        return selectWithinBudgetSeconds(prioritizedCards, budgetSeconds, this::estimateCardSeconds);
    }

    /** A card's estimated seconds is the average of its recent response times plus a fixed reveal/rating overhead. */
    static int estimateCardSeconds(List<ReviewHistory> recentReviews) {
        List<Integer> responseSeconds = recentReviews == null ? List.of() : recentReviews.stream()
                .map(ReviewHistory::getResponseTimeMs)
                .filter(Objects::nonNull)
                .map(millis -> (int) Math.round(millis / 1000.0))
                .toList();
        int averageResponseSeconds = responseSeconds.isEmpty()
                ? DEFAULT_RESPONSE_SECONDS
                : (int) Math.round(responseSeconds.stream().mapToInt(Integer::intValue).average().orElse(DEFAULT_RESPONSE_SECONDS));
        return averageResponseSeconds + REVEAL_AND_RATE_OVERHEAD_SECONDS;
    }

    static List<Flashcard> selectWithinBudget(List<Flashcard> prioritizedCards, int budgetMinutes,
                                              ToIntFunction<Flashcard> estimator) {
        return selectWithinBudgetSeconds(prioritizedCards, Math.max(0, budgetMinutes) * 60, estimator);
    }

    /**
     * Pure, DB-free selection: walks the whole prioritized list and includes every card that
     * still fits in the remaining budget. A card whose own estimate would exceed the remaining
     * budget is skipped (not treated as a stopping point), so one very large, high-priority card
     * never blocks smaller, lower-priority cards later in the list from being included — even
     * when that large card is first. Only if literally nothing fits does it fall back to the
     * single highest-priority card, so a session never starts completely empty.
     *
     * <p>Use {@link #selectFittingWithinBudgetSeconds} instead when topping up an already
     * non-empty selection (e.g. filling leftover budget with optional content) — the "never
     * empty" fallback here is only appropriate when this is the only thing being selected for
     * the session.
     */
    static List<Flashcard> selectWithinBudgetSeconds(List<Flashcard> prioritizedCards, int budgetSeconds,
                                                      ToIntFunction<Flashcard> estimator) {
        List<Flashcard> selected = selectFittingWithinBudgetSeconds(prioritizedCards, budgetSeconds, estimator);
        if (selected.isEmpty() && !prioritizedCards.isEmpty()) {
            selected.add(prioritizedCards.get(0));
        }
        return selected;
    }

    /** Like {@link #selectWithinBudgetSeconds} but never force-includes a card that doesn't fit. */
    static List<Flashcard> selectFittingWithinBudgetSeconds(List<Flashcard> prioritizedCards, int budgetSeconds,
                                                             ToIntFunction<Flashcard> estimator) {
        List<Flashcard> selected = new ArrayList<>();
        int usedSeconds = 0;
        for (Flashcard card : prioritizedCards) {
            int cardSeconds = estimator.applyAsInt(card);
            if (usedSeconds + cardSeconds <= budgetSeconds) {
                selected.add(card);
                usedSeconds += cardSeconds;
            }
        }
        return selected;
    }
}
