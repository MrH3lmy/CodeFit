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
 * a default) and selects a prefix of an already-prioritized queue whose estimated total time fits
 * a learner's chosen time budget.
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

    /**
     * Pure, DB-free selection: walks the prioritized list and keeps adding cards while they fit
     * the budget. Always includes at least one card (if any exist) even if its own estimate alone
     * exceeds the budget, so a session never starts completely empty.
     */
    static List<Flashcard> selectWithinBudget(List<Flashcard> prioritizedCards, int budgetMinutes,
                                              ToIntFunction<Flashcard> estimator) {
        int budgetSeconds = Math.max(0, budgetMinutes) * 60;
        List<Flashcard> selected = new ArrayList<>();
        int usedSeconds = 0;
        for (Flashcard card : prioritizedCards) {
            int cardSeconds = estimator.applyAsInt(card);
            if (!selected.isEmpty() && usedSeconds + cardSeconds > budgetSeconds) {
                break;
            }
            selected.add(card);
            usedSeconds += cardSeconds;
            if (usedSeconds >= budgetSeconds) {
                break;
            }
        }
        return selected;
    }
}
