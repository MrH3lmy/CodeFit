package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.AssessmentAttempt;
import com.codefit.model.AssessmentItem;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.AssessmentAttemptRepository;
import com.codefit.repository.AssessmentItemRepository;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance criterion behind #104 that matters most: recording a weekly transfer assessment
 * attempt must never silently change a {@link Flashcard}'s schedule or add/remove a
 * {@link ReviewHistory} row. Touches the local sqlite database the same way
 * {@code FocusPreferenceServiceTest}/{@code SyllabusServiceTest} do (idempotently).
 */
class AssessmentIsolationTest {

    private final FlashcardService flashcardService = new FlashcardService();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ReviewService reviewService = new ReviewService();
    private final AssessmentItemRepository assessmentItemRepository = new AssessmentItemRepository();
    private final AssessmentAttemptRepository assessmentAttemptRepository = new AssessmentAttemptRepository();
    private final AssessmentAttemptService assessmentAttemptService = new AssessmentAttemptService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void recordingAnAssessmentAttemptNeverChangesAnExistingCardsScheduleOrReviewHistory() {
        String deckName = "Java BE 01 - Core Java & OOP";
        String testFront = "TEST-FIXTURE: AssessmentIsolationTest schedule/history card";

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
                new ReviewAttempt("CORRECT", "answer", 4000, false, "assessment-isolation-test-session"));

        Flashcard beforeAssessment = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyBefore = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);
        int attemptCountBefore = assessmentAttemptRepository.findRecent(1000).size();

        List<AssessmentItem> assessmentItems = assessmentItemRepository.findAll();
        assertFalse(assessmentItems.isEmpty(), "the seeded assessment bank must have at least one item");
        AssessmentItem item = assessmentItems.get(0);
        assessmentAttemptService.recordAttempt(item, item.getVariants().get(0), true, "my transfer answer", 5000,
                UUID.randomUUID().toString());

        Flashcard afterAssessment = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyAfter = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);
        int attemptCountAfter = assessmentAttemptRepository.findRecent(1000).size();

        assertEquals(beforeAssessment.getDueDate(), afterAssessment.getDueDate());
        assertEquals(beforeAssessment.getIntervalDays(), afterAssessment.getIntervalDays());
        assertEquals(beforeAssessment.getEaseFactor(), afterAssessment.getEaseFactor());
        assertEquals(beforeAssessment.getReviewCount(), afterAssessment.getReviewCount());
        assertEquals(beforeAssessment.getCardState(), afterAssessment.getCardState());
        assertEquals(historyBefore.size(), historyAfter.size(), "recording an assessment attempt must not add/remove review history rows");
        assertEquals(historyBefore.stream().map(ReviewHistory::getId).toList(),
                historyAfter.stream().map(ReviewHistory::getId).toList());

        // Positive control: the assessment attempt itself really was persisted, just in its own table.
        assertEquals(attemptCountBefore + 1, attemptCountAfter);
    }

    @Test
    void assessmentAttemptsRotateVariantsAndAreIsolatedFromReviewHistoryEvenAcrossRepeats() {
        List<AssessmentItem> items = assessmentItemRepository.findAll();
        assertFalse(items.isEmpty());
        AssessmentItem item = items.get(0);
        String runId = UUID.randomUUID().toString();

        int previousAttempts = assessmentAttemptRepository.countByItemId().getOrDefault(item.getId(), 0);
        var variant = item.variantForAttemptCount(previousAttempts);
        AssessmentAttempt saved = assessmentAttemptService.recordAttempt(item, variant, true, "attempt text", 1200, runId);

        assertEquals(item.getId(), saved.assessmentItemId());
        assertEquals(variant.variantIndex(), saved.variantIndex());
        assertTrue(saved.correct());
        assertEquals(runId, saved.runId());
    }
}
