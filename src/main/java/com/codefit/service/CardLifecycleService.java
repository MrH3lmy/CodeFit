package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.service.MasteryService.CardMasteryState;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Centralizes card lifecycle transitions so every review path (normal, boss battle, relearning
 * retries) applies the same rules instead of scattering ad hoc state changes across services.
 */
public class CardLifecycleService {

    /** Bounds for a diagnostic graduation interval (see {@link #graduate(Flashcard, int)}); the
     *  caller's requested interval is clamped into this range rather than trusted outright. */
    public static final int MIN_GRADUATION_INTERVAL_DAYS = 30;
    public static final int MAX_GRADUATION_INTERVAL_DAYS = 60;
    public static final int DEFAULT_GRADUATION_INTERVAL_DAYS = 45;

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

    /**
     * Diagnostically graduates a card the learner already knows straight out of the normal
     * learning phase: instead of the short intervals a single Easy rating would produce, it jumps
     * to a long, configurable interval (clamped to {@link #MIN_GRADUATION_INTERVAL_DAYS}-
     * {@link #MAX_GRADUATION_INTERVAL_DAYS} days) and is marked {@link CardState#GRADUATED} so it
     * resurfaces later for a retention check rather than being trusted forever. Callers must gate
     * this on {@link RatingGuardrail#canGraduate} themselves — this method does not re-validate
     * correctness/hint/timing, only applies the resulting state change. A no-op on a suspended
     * card, matching {@link #applyReviewOutcome}.
     */
    public Flashcard graduate(Flashcard card, int intervalDays) {
        if (card.getCardState() == CardState.SUSPENDED) {
            return card;
        }
        if (card.getIntroducedAt() == null) {
            card.setIntroducedAt(LocalDateTime.now());
        }
        int clampedInterval = Math.max(MIN_GRADUATION_INTERVAL_DAYS, Math.min(MAX_GRADUATION_INTERVAL_DAYS, intervalDays));
        card.setCardState(CardState.GRADUATED);
        card.setIntervalDays(clampedInterval);
        card.setDueDate(LocalDate.now().plusDays(clampedInterval));
        card.setReviewCount(card.getReviewCount() + 1);
        return card;
    }

    /**
     * Removes a card from every review queue until it is explicitly reactivated. Unlike
     * {@link #applyReviewOutcome}, this is intentionally allowed from any state (including
     * re-suspending an already-suspended card, which is a harmless no-op) since a learner may want
     * to suspend a card they've already progressed past.
     */
    public Flashcard suspend(Flashcard card) {
        card.setCardState(CardState.SUSPENDED);
        return card;
    }
}
