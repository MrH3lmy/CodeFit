package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.model.ValidationMode;
import com.codefit.service.MasteryService.CardMasteryState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CardLifecycleServiceTest {

    private final CardLifecycleService service = new CardLifecycleService();

    private Flashcard card(CardState state) {
        Flashcard flashcard = new Flashcard(1, "front", "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setCardState(state);
        return flashcard;
    }

    @Test
    void newCardRatedAgainStaysLearningNotRelearning() {
        Flashcard card = card(CardState.NEW);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.NOT_SEEN);
        assertEquals(CardState.LEARNING, card.getCardState());
    }

    @Test
    void learningCardRatedAgainStaysLearning() {
        Flashcard card = card(CardState.LEARNING);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.LEARNING, card.getCardState());
    }

    @Test
    void reviewCardRatedAgainMovesToRelearning() {
        Flashcard card = card(CardState.REVIEW);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void masteredCardRatedAgainMovesToRelearning() {
        Flashcard card = card(CardState.MASTERED);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void relearningCardRatedAgainStaysRelearning() {
        Flashcard card = card(CardState.RELEARNING);
        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(CardState.RELEARNING, card.getCardState());
    }

    @Test
    void passingRatingGraduatesToReviewWhenNotYetMastered() {
        Flashcard newCard = card(CardState.NEW);
        service.applyReviewOutcome(newCard, ReviewRating.GOOD, CardMasteryState.LEARNING);
        assertEquals(CardState.REVIEW, newCard.getCardState());

        Flashcard relearningCard = card(CardState.RELEARNING);
        service.applyReviewOutcome(relearningCard, ReviewRating.HARD, CardMasteryState.LEARNING);
        assertEquals(CardState.REVIEW, relearningCard.getCardState());
    }

    @Test
    void passingRatingMovesToMasteredWhenMasteryServiceSaysSo() {
        Flashcard card = card(CardState.REVIEW);
        service.applyReviewOutcome(card, ReviewRating.EASY, CardMasteryState.MASTERED);
        assertEquals(CardState.MASTERED, card.getCardState());
    }

    @Test
    void suspendedCardIsNeverReactivatedByAReview() {
        Flashcard card = card(CardState.SUSPENDED);
        service.applyReviewOutcome(card, ReviewRating.EASY, CardMasteryState.MASTERED);
        assertEquals(CardState.SUSPENDED, card.getCardState());

        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.NOT_SEEN);
        assertEquals(CardState.SUSPENDED, card.getCardState());
    }

    @Test
    void introducedAtIsSetOnceOnFirstReviewAndNeverOverwritten() {
        Flashcard card = card(CardState.NEW);
        assertNull(card.getIntroducedAt());

        service.applyReviewOutcome(card, ReviewRating.GOOD, CardMasteryState.LEARNING);
        assertNotNull(card.getIntroducedAt());
        var firstIntroducedAt = card.getIntroducedAt();

        service.applyReviewOutcome(card, ReviewRating.AGAIN, CardMasteryState.LEARNING);
        assertEquals(firstIntroducedAt, card.getIntroducedAt());
    }

    @Test
    void suspendedCardIntroducedAtIsNotSet() {
        Flashcard card = card(CardState.SUSPENDED);
        service.applyReviewOutcome(card, ReviewRating.GOOD, CardMasteryState.MASTERED);
        assertNull(card.getIntroducedAt());
    }
}
