package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.ComplexityClass;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.Problem;
import com.codefit.model.ReflectionCardSource;
import com.codefit.model.SolvedWith;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reflection-to-flashcard bridge (#148): pre-filled drafts stay fully editable before
 * save, saving reuses ordinary {@link FlashcardService} validation/scheduling, duplicate creation
 * from the same (problem, reflection field) pair is caught, and deleting the source problem never
 * takes an already-created flashcard down with it, using a fresh unique platform/external-code per
 * test like the rest of this suite.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProblemFlashcardServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemFlashcardService problemFlashcardService = new ProblemFlashcardService();

    private long fixtureProblemId(String testName) {
        Problem problem = problemService.findOrCreateProblem("TEST-FIXTURE-FLASHCARD", testName + "-" + UUID.randomUUID(),
                "Flashcard Fixture " + testName, "https://example.test/" + testName, "General", null, List.of());
        return problem.getId();
    }

    @Test
    void draftsPreFillFromTheMatchingReflectionField() {
        long problemId = fixtureProblemId("prefill");
        progressService.updateReflection(problemId, new ProblemReflection(6, SolvedWith.SELF, null,
                "binary search approach", "off-by-one error", "watch the boundary", ComplexityClass.O_LOG_N,
                ComplexityClass.O_1, "always check both ends", "Binary Search", true, false, true, false));

        assertEquals("always check both ends", problemFlashcardService.buildDraft(problemId, ReflectionCardSource.LESSON_LEARNED).back());
        assertEquals("off-by-one error", problemFlashcardService.buildDraft(problemId, ReflectionCardSource.MISTAKE_MADE).back());
        assertEquals("watch the boundary", problemFlashcardService.buildDraft(problemId, ReflectionCardSource.KEY_OBSERVATION).back());
        assertEquals("Binary Search", problemFlashcardService.buildDraft(problemId, ReflectionCardSource.ALGORITHM_OR_TECHNIQUE).back());
        assertTrue(problemFlashcardService.buildDraft(problemId, ReflectionCardSource.COMPLEXITY_TRADEOFF).back().contains("o log n"));
    }

    @Test
    void edgeCaseDraftStartsBlankSinceThereIsNoBackingStoredField() {
        long problemId = fixtureProblemId("edge-case-blank");

        ProblemFlashcardService.ProblemFlashcardDraft draft = problemFlashcardService.buildDraft(problemId, ReflectionCardSource.EDGE_CASE);

        assertEquals("", draft.back());
        assertFalse(draft.front().isBlank(), "the prompt itself is still generated even though the answer starts blank");
    }

    @Test
    void savingReusesOrdinaryFlashcardValidationAndSchedulingDefaults() {
        long problemId = fixtureProblemId("scheduling-defaults");

        ProblemFlashcardService.ProblemFlashcardCreationResult result = problemFlashcardService.createCard(
                problemFlashcardService.resolveLessonsDeckId(), problemId, ReflectionCardSource.LESSON_LEARNED,
                "What did you learn?", "Two pointers beats brute force here.", false);

        Flashcard card = result.card();
        assertFalse(result.alreadyLinked());
        assertEquals(CardType.CONCEPT, card.getCardType());
        assertEquals(CardState.NEW, card.getCardState());
        assertEquals(LocalDate.now(), card.getDueDate());
        assertEquals(0, card.getReviewCount());
    }

    @Test
    void savedCardIsFullyEditableBeforeSaveRatherThanForcedToMatchTheDraft() {
        long problemId = fixtureProblemId("editable-before-save");
        progressService.updateReflection(problemId, new ProblemReflection(null, null, null, null, null,
                null, null, null, "original lesson text", null, false, false, false, false));

        // The learner is free to rewrite the pre-filled draft before saving; createCard must persist
        // exactly what they typed, not silently re-pull "original lesson text" from progress.
        ProblemFlashcardService.ProblemFlashcardCreationResult result = problemFlashcardService.createCard(
                problemFlashcardService.resolveLessonsDeckId(), problemId, ReflectionCardSource.LESSON_LEARNED,
                "Edited prompt the learner typed", "Edited answer the learner typed", false);

        assertEquals("Edited prompt the learner typed", result.card().getFront());
        assertEquals("Edited answer the learner typed", result.card().getBack());
    }

    @Test
    void creatingASecondCardFromTheSameProblemAndFieldIsSkippedByDefault() {
        long problemId = fixtureProblemId("duplicate-skip");
        long deckId = problemFlashcardService.resolveLessonsDeckId();

        ProblemFlashcardService.ProblemFlashcardCreationResult first = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.MISTAKE_MADE, "First prompt", "First answer", false);
        ProblemFlashcardService.ProblemFlashcardCreationResult second = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.MISTAKE_MADE, "Second prompt", "Second answer", false);

        assertFalse(first.alreadyLinked());
        assertTrue(second.alreadyLinked());
        assertEquals(first.card().getId(), second.card().getId());
    }

    @Test
    void allowDuplicateBypassesTheDuplicateCheckAndCreatesAGenuineSecondCard() {
        long problemId = fixtureProblemId("duplicate-allowed");
        long deckId = problemFlashcardService.resolveLessonsDeckId();

        ProblemFlashcardService.ProblemFlashcardCreationResult first = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.KEY_OBSERVATION, "First prompt", "First answer", false);
        ProblemFlashcardService.ProblemFlashcardCreationResult second = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.KEY_OBSERVATION, "Second prompt", "Second answer", true);

        assertFalse(second.alreadyLinked());
        assertTrue(first.card().getId() != second.card().getId());
    }

    @Test
    void differentReflectionFieldsOnTheSameProblemAreNotTreatedAsDuplicatesOfEachOther() {
        long problemId = fixtureProblemId("different-fields");
        long deckId = problemFlashcardService.resolveLessonsDeckId();

        ProblemFlashcardService.ProblemFlashcardCreationResult lesson = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.LESSON_LEARNED, "Lesson prompt", "Lesson answer", false);
        ProblemFlashcardService.ProblemFlashcardCreationResult mistake = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.MISTAKE_MADE, "Mistake prompt", "Mistake answer", false);

        assertFalse(lesson.alreadyLinked());
        assertFalse(mistake.alreadyLinked());
        assertTrue(lesson.card().getId() != mistake.card().getId());
    }

    @Test
    void resolveLessonsDeckIdCreatesTheDeckOnceAndReusesItAfterThat() {
        long firstCall = problemFlashcardService.resolveLessonsDeckId();
        long secondCall = problemFlashcardService.resolveLessonsDeckId();

        assertEquals(firstCall, secondCall);
        long matchingDecks = problemFlashcardService.getAvailableDecks().stream()
                .filter(deck -> deck.getName().equalsIgnoreCase(ProblemFlashcardService.LESSONS_DECK_NAME))
                .count();
        assertEquals(1, matchingDecks);
    }

    @Test
    void cardsCanBeFiledIntoAnyExistingDeckNotJustTheLessonsDeck() {
        long problemId = fixtureProblemId("custom-deck");
        Deck customDeck = new com.codefit.repository.DeckRepository().save(new Deck("Custom Deck " + UUID.randomUUID(), "Test deck"));

        ProblemFlashcardService.ProblemFlashcardCreationResult result = problemFlashcardService.createCard(
                customDeck.getId(), problemId, ReflectionCardSource.LESSON_LEARNED, "Prompt", "Answer", false);

        assertEquals(customDeck.getId(), result.card().getDeckId());
    }

    @Test
    void resolveSourceProblemReturnsTheLinkedProblemAndEmptyForAnUnlinkedCard() {
        long problemId = fixtureProblemId("resolve-source");
        long deckId = problemFlashcardService.resolveLessonsDeckId();

        ProblemFlashcardService.ProblemFlashcardCreationResult linked = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.LESSON_LEARNED, "Prompt", "Answer", false);
        Flashcard unlinkedCard = new FlashcardService().addCard(deckId, "Unrelated prompt", "Unrelated answer");

        Optional<Problem> resolved = problemFlashcardService.resolveSourceProblem(linked.card());
        Optional<Problem> notResolved = problemFlashcardService.resolveSourceProblem(unlinkedCard);

        assertTrue(resolved.isPresent());
        assertEquals(problemId, resolved.get().getId());
        assertTrue(notResolved.isEmpty());
    }

    @Test
    void deletingTheSourceProblemDoesNotDeleteAnAlreadyCreatedFlashcard() throws SQLException {
        long problemId = fixtureProblemId("delete-problem");
        long deckId = problemFlashcardService.resolveLessonsDeckId();
        ProblemFlashcardService.ProblemFlashcardCreationResult result = problemFlashcardService.createCard(
                deckId, problemId, ReflectionCardSource.LESSON_LEARNED, "Prompt", "Answer", false);
        long cardId = result.card().getId();

        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM problems WHERE id = ?")) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        }

        Optional<Flashcard> stillThere = new FlashcardService().getCardById(cardId);
        assertTrue(stillThere.isPresent(), "the flashcard must survive its source problem being deleted");
        assertEquals(problemId, stillThere.get().getSourceProblemId());
    }
}
