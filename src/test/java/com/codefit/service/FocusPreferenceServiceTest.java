package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies switching the active focus module (#110) is a pure preference change: it must never
 * touch a flashcard's schedule or its review history, only which module new/stretch cards favor.
 * Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class FocusPreferenceServiceTest {

    private final FocusPreferenceService focusPreferenceService = new FocusPreferenceService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ReviewService reviewService = new ReviewService();

    @Test
    void switchingFocusNeverChangesAnExistingCardsScheduleOrReviewHistory() {
        String deckName = "Java BE 01 - Core Java & OOP";
        String testFront = "TEST-FIXTURE: FocusPreferenceServiceTest schedule/history card";

        DeckRepository deckRepository = new DeckRepository();
        Deck deck = deckRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(deckName))
                .findFirst()
                .orElseThrow();

        if (!flashcardService.cardExistsInDeck(deck.getId(), testFront)) {
            flashcardService.addCard(deck.getId(), testFront, "answer");
        }
        Flashcard card = flashcardService.getAllCards().stream()
                .filter(candidate -> candidate.getDeckId() == deck.getId() && testFront.equals(candidate.getFront()))
                .findFirst()
                .orElseThrow();

        reviewService.review(card, ReviewRating.GOOD, true,
                new ReviewAttempt("CORRECT", "answer", 4000, false, "focus-preference-test-session"));

        Flashcard reviewedCard = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyBefore = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);

        focusPreferenceService.setFocus("Java Backend", 1);
        focusPreferenceService.setFocus("Advanced Backend Engineering", 3);
        focusPreferenceService.setMatureInterleavePercent(30);

        Flashcard afterFocusChanges = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyAfter = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);

        assertEquals(reviewedCard.getDueDate(), afterFocusChanges.getDueDate());
        assertEquals(reviewedCard.getIntervalDays(), afterFocusChanges.getIntervalDays());
        assertEquals(reviewedCard.getEaseFactor(), afterFocusChanges.getEaseFactor());
        assertEquals(reviewedCard.getReviewCount(), afterFocusChanges.getReviewCount());
        assertEquals(reviewedCard.getCardState(), afterFocusChanges.getCardState());
        assertEquals(historyBefore.size(), historyAfter.size(), "switching focus must not add/remove review history rows");
        assertEquals(historyBefore.stream().map(ReviewHistory::getId).toList(),
                historyAfter.stream().map(ReviewHistory::getId).toList());
    }

    @Test
    void setFocusAndClearFocusOnlyChangeThePreferencePointer() {
        focusPreferenceService.setFocus("Java Backend", 4);
        UserProgress afterSet = focusPreferenceService.getPreference();
        assertEquals("Java Backend", afterSet.getActiveTrainingPath());
        assertEquals(4, afterSet.getFocusModuleOrder());
        assertTrue(afterSet.hasFocusModule());

        focusPreferenceService.clearFocus();
        UserProgress afterClear = focusPreferenceService.getPreference();
        assertFalse(afterClear.hasFocusModule());
    }

    @Test
    void matureInterleavePercentIsClampedToASmallShare() {
        focusPreferenceService.setMatureInterleavePercent(-10);
        assertEquals(FocusPreferenceService.MIN_MATURE_INTERLEAVE_PERCENT, focusPreferenceService.getPreference().getMatureInterleavePercent());

        focusPreferenceService.setMatureInterleavePercent(9000);
        assertEquals(FocusPreferenceService.MAX_MATURE_INTERLEAVE_PERCENT, focusPreferenceService.getPreference().getMatureInterleavePercent());
    }

    @Test
    void getFocusDeckIdsResolvesTheChosenModulesRealDeck() {
        focusPreferenceService.setFocus("Java Backend", 3);
        java.util.Set<Long> deckIds = focusPreferenceService.getFocusDeckIds();

        DeckRepository deckRepository = new DeckRepository();
        Deck expectedDeck = deckRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase("Java BE 03 - JDBC & SQL"))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.Set.of(expectedDeck.getId()), deckIds);
    }
}
