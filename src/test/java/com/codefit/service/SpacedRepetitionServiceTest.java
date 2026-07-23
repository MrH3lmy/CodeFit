package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpacedRepetitionServiceTest {

    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 7, 23);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final SpacedRepetitionService service = new SpacedRepetitionService(FIXED_CLOCK);

    private Flashcard newCard(int intervalDays, double easeFactor) {
        Flashcard card = new Flashcard(1, "front", "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        card.setIntervalDays(intervalDays);
        card.setEaseFactor(easeFactor);
        return card;
    }

    @Test
    void againResetsIntervalToZeroAndDueToday() {
        Flashcard card = newCard(10, 2.5);
        service.applyReview(card, ReviewRating.AGAIN);

        assertEquals(0, card.getIntervalDays());
        assertEquals(FIXED_TODAY, card.getDueDate());
    }

    @Test
    void againLowersEaseButNeverBelowMinimum() {
        Flashcard nearFloor = newCard(5, 1.35);
        service.applyReview(nearFloor, ReviewRating.AGAIN);
        assertEquals(1.3, nearFloor.getEaseFactor());

        Flashcard atFloor = newCard(5, 1.3);
        service.applyReview(atFloor, ReviewRating.AGAIN);
        assertEquals(1.3, atFloor.getEaseFactor());
    }

    @Test
    void hardGrowsIntervalModestlyAndLowersEase() {
        Flashcard card = newCard(10, 2.5);
        service.applyReview(card, ReviewRating.HARD);

        assertEquals(12, card.getIntervalDays());
        assertEquals(2.35, card.getEaseFactor());
        assertEquals(FIXED_TODAY.plusDays(12), card.getDueDate());
    }

    @Test
    void hardOnNewCardScheduleAtLeastOneDay() {
        Flashcard card = newCard(0, 2.5);
        service.applyReview(card, ReviewRating.HARD);
        assertTrue(card.getIntervalDays() >= 1);
    }

    @Test
    void goodOnBrandNewCardSchedulesOneDay() {
        Flashcard card = newCard(0, 2.5);
        service.applyReview(card, ReviewRating.GOOD);
        assertEquals(1, card.getIntervalDays());
    }

    @Test
    void goodMultipliesIntervalByEaseFactor() {
        Flashcard card = newCard(10, 2.0);
        service.applyReview(card, ReviewRating.GOOD);
        assertEquals(20, card.getIntervalDays());
        assertEquals(2.0, card.getEaseFactor());
    }

    @Test
    void easyOnBrandNewCardSchedulesFourDaysAndRaisesEase() {
        Flashcard card = newCard(0, 2.5);
        service.applyReview(card, ReviewRating.EASY);
        assertEquals(4, card.getIntervalDays());
        assertEquals(2.65, card.getEaseFactor());
    }

    @Test
    void easyEnforcesMinimumFourDayInterval() {
        Flashcard card = newCard(1, 1.3);
        service.applyReview(card, ReviewRating.EASY);
        assertTrue(card.getIntervalDays() >= 4);
    }

    @Test
    void reviewCountIncrementsOnEveryRating() {
        Flashcard card = newCard(0, 2.5);
        assertEquals(0, card.getReviewCount());
        service.applyReview(card, ReviewRating.GOOD);
        assertEquals(1, card.getReviewCount());
        service.applyReview(card, ReviewRating.AGAIN);
        assertEquals(2, card.getReviewCount());
    }
}
