package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBudgetServiceTest {

    private Flashcard card(long id) {
        Flashcard flashcard = new Flashcard(1, "front " + id, "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(id);
        return flashcard;
    }

    private ReviewHistory reviewWithResponseTime(Integer responseTimeMs) {
        return new ReviewHistory(0, 1, ReviewRating.GOOD, 0, 1, LocalDateTime.now(), true, false,
                "EXACT", "attempt", responseTimeMs, false, "session");
    }

    @Test
    void estimateUsesDefaultResponseTimeWhenNoHistoryExists() {
        int estimate = SessionBudgetService.estimateCardSeconds(List.of());
        assertEquals(SessionBudgetService.DEFAULT_RESPONSE_SECONDS + SessionBudgetService.REVEAL_AND_RATE_OVERHEAD_SECONDS, estimate);
    }

    @Test
    void estimateAveragesRecentResponseTimesPlusOverhead() {
        List<ReviewHistory> history = List.of(
                reviewWithResponseTime(10_000),
                reviewWithResponseTime(20_000)
        );
        int estimate = SessionBudgetService.estimateCardSeconds(history);
        assertEquals(15 + SessionBudgetService.REVEAL_AND_RATE_OVERHEAD_SECONDS, estimate);
    }

    @Test
    void estimateIgnoresReviewsWithoutRecordedResponseTime() {
        List<ReviewHistory> history = List.of(reviewWithResponseTime(null), reviewWithResponseTime(null));
        int estimate = SessionBudgetService.estimateCardSeconds(history);
        assertEquals(SessionBudgetService.DEFAULT_RESPONSE_SECONDS + SessionBudgetService.REVEAL_AND_RATE_OVERHEAD_SECONDS, estimate);
    }

    @Test
    void selectWithinBudgetFillsUpToTheTimeLimit() {
        List<Flashcard> cards = List.of(card(1), card(2), card(3), card(4));
        List<Flashcard> selected = SessionBudgetService.selectWithinBudget(cards, 1, ignored -> 20);

        // 60 seconds budget / 20s per card = exactly 3 cards.
        assertEquals(3, selected.size());
    }

    @Test
    void selectWithinBudgetAlwaysIncludesAtLeastOneCardEvenIfItExceedsBudget() {
        List<Flashcard> cards = List.of(card(1), card(2));
        List<Flashcard> selected = SessionBudgetService.selectWithinBudget(cards, 1, ignored -> 90);

        assertEquals(1, selected.size());
    }

    @Test
    void selectWithinBudgetReturnsEmptyForEmptyQueue() {
        List<Flashcard> selected = SessionBudgetService.selectWithinBudget(List.of(), 15, ignored -> 20);
        assertTrue(selected.isEmpty());
    }

    @Test
    void selectWithinBudgetHonorsPerCardVariableEstimates() {
        List<Flashcard> cards = List.of(card(1), card(2), card(3));
        List<Flashcard> selected = SessionBudgetService.selectWithinBudget(cards, 1,
                flashcard -> flashcard.getId() == 1 ? 50 : 5);

        // card1=50s, card2=5s -> 55s fits in 60s budget; card3 would push to 60s exactly (still fits).
        assertEquals(3, selected.size());
    }
}
