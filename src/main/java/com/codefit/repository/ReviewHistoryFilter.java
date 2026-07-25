package com.codefit.repository;

import com.codefit.model.CardType;

import java.time.LocalDateTime;

/**
 * Scopes a review-history query by date range, deck, skill, and card type so learning-efficiency
 * metrics (see StatsService) can be broken down the same way the issue's acceptance criteria
 * require, instead of only ever reading the full unfiltered history. Any field left {@code null}
 * (or blank, for skillCategory) is not applied as a constraint.
 */
public record ReviewHistoryFilter(LocalDateTime start, LocalDateTime end, Long deckId, String skillCategory,
                                   CardType cardType) {
    private static final ReviewHistoryFilter ALL = new ReviewHistoryFilter(null, null, null, null, null);

    public static ReviewHistoryFilter all() {
        return ALL;
    }
}
