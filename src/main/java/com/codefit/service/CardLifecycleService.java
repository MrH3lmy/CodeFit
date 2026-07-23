package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.service.MasteryService.CardMasteryState;

import java.time.LocalDateTime;

/**
 * Centralizes card lifecycle transitions so every review path (normal, boss battle, relearning
 * retries) applies the same rules instead of scattering ad hoc state changes across services.
 */
public class CardLifecycleService {

    /**
     * Applies the outcome of a single review to a card's lifecycle state and records when a card
     * was first introduced (left NEW). Mutates and returns the given card; callers persist it.
     */
    public Flashcard applyReviewOutcome(Flashcard card, ReviewRating rating, CardMasteryState masteryState) {
        CardState current = card.getCardState();
        if (current == CardState.SUSPENDED) {
            return card;
        }
        if (card.getIntroducedAt() == null) {
            card.setIntroducedAt(LocalDateTime.now());
        }
        card.setCardState(nextState(current, rating, masteryState));
        return card;
    }

    private CardState nextState(CardState current, ReviewRating rating, CardMasteryState masteryState) {
        if (rating == ReviewRating.AGAIN) {
            return current == CardState.NEW || current == CardState.LEARNING
                    ? CardState.LEARNING
                    : CardState.RELEARNING;
        }
        if (masteryState == CardMasteryState.MASTERED) {
            return CardState.MASTERED;
        }
        return CardState.REVIEW;
    }
}
