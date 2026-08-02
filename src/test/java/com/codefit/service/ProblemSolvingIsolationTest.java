package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The acceptance criterion behind #142 that matters most: problem-solving training must be a
 * workflow entirely separate from flashcard review. Recording problem-solving activity must never
 * change a {@link Flashcard}'s schedule or {@link ReviewHistory}, and normal flashcard review must
 * never change a problem's progress or attempts.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProblemSolvingIsolationTest {

    private final FlashcardService flashcardService = new FlashcardService();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ReviewService reviewService = new ReviewService();
    private final ProblemService problemService = new ProblemService();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemAttemptService attemptService = new ProblemAttemptService();

    @Test
    void recordingProblemSolvingActivityNeverChangesAnExistingCardsScheduleOrReviewHistory() {
        String deckName = "Java BE 01 - Core Java & OOP";
        String testFront = "TEST-FIXTURE: ProblemSolvingIsolationTest schedule/history card";

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
                new ReviewAttempt("CORRECT", "answer", 4000, false, "problem-solving-isolation-test-session"));

        Flashcard beforeProblemActivity = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyBefore = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);

        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-ISOLATION-1",
                "Isolation Fixture", null, "General", null, List.of());
        progressService.updateProgress(problem.getId(), ProblemState.SOLVED, null);
        attemptService.recordAttempt(problem.getId(), SubmissionResult.AC, null, null, null, null, null);

        Flashcard afterProblemActivity = flashcardRepository.findById(card.getId()).orElseThrow();
        List<ReviewHistory> historyAfter = reviewHistoryRepository.findRecentForFlashcard(card.getId(), 20);

        assertEquals(beforeProblemActivity.getDueDate(), afterProblemActivity.getDueDate());
        assertEquals(beforeProblemActivity.getIntervalDays(), afterProblemActivity.getIntervalDays());
        assertEquals(beforeProblemActivity.getEaseFactor(), afterProblemActivity.getEaseFactor());
        assertEquals(beforeProblemActivity.getReviewCount(), afterProblemActivity.getReviewCount());
        assertEquals(beforeProblemActivity.getCardState(), afterProblemActivity.getCardState());
        assertEquals(historyBefore.size(), historyAfter.size(),
                "recording problem-solving activity must not add/remove review history rows");
    }

    @Test
    void reviewingAFlashcardNeverChangesAnExistingProblemsProgressOrAttemptCount() {
        String deckName = "Java BE 01 - Core Java & OOP";
        String testFront = "TEST-FIXTURE: ProblemSolvingIsolationTest reverse-isolation card";

        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", "TF-142-ISOLATION-2",
                "Reverse Isolation Fixture", null, "General", null, List.of());
        progressService.updateProgress(problem.getId(), ProblemState.IN_PROGRESS, null);
        progressService.updateReflection(problem.getId(), new ProblemReflection(null, null, null,
                "still working on it", null, null, null, null, null, null, false, false, false, false));
        int attemptCountBefore = attemptService.getAttempts(problem.getId()).size();
        ProblemState stateBefore = progressService.getOrCreate(problem.getId()).getState();

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
                new ReviewAttempt("CORRECT", "answer", 4000, false, "reverse-isolation-test-session"));

        int attemptCountAfter = attemptService.getAttempts(problem.getId()).size();
        ProblemState stateAfter = progressService.getOrCreate(problem.getId()).getState();

        assertEquals(attemptCountBefore, attemptCountAfter);
        assertEquals(stateBefore, stateAfter);
    }
}
