package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;

import java.time.Clock;
import java.time.LocalDate;

public class SpacedRepetitionService {
    private static final double MIN_EASE = 1.3;

    private final Clock clock;

    public SpacedRepetitionService() {
        this(Clock.systemDefaultZone());
    }

    public SpacedRepetitionService(Clock clock) {
        this.clock = clock;
    }

    public Flashcard applyReview(Flashcard card, ReviewRating rating) {
        int interval = card.getIntervalDays();
        double ease = card.getEaseFactor();
        int nextInterval;

        switch (rating) {
            case AGAIN -> {
                nextInterval = 0;
                ease = Math.max(MIN_EASE, ease - 0.20);
            }
            case HARD -> {
                nextInterval = Math.max(1, (int) Math.ceil(interval * 1.2));
                ease = Math.max(MIN_EASE, ease - 0.15);
            }
            case GOOD -> nextInterval = interval == 0 ? 1 : Math.max(1, (int) Math.round(interval * ease));
            case EASY -> {
                nextInterval = interval == 0 ? 4 : Math.max(4, (int) Math.round(interval * ease * 1.3));
                ease += 0.15;
            }
            default -> throw new IllegalArgumentException("Unsupported review rating: " + rating);
        }

        card.setIntervalDays(nextInterval);
        card.setEaseFactor(roundEase(ease));
        card.setDueDate(LocalDate.now(clock).plusDays(nextInterval));
        card.setReviewCount(card.getReviewCount() + 1);
        return card;
    }

    private double roundEase(double ease) {
        return Math.round(ease * 100.0) / 100.0;
    }
}
