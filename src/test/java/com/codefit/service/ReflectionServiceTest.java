package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.GeneratedCard;
import com.codefit.model.ReflectionDraft;
import com.codefit.model.ReflectionType;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the #102 acceptance criteria that don't need a UI: each reflection workflow splits into
 * atomic, independently-answerable cards; duplicate detection runs per generated card; and
 * reflection XP is awarded exactly once per reflection no matter how many cards it produced.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ReflectionServiceTest {

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final ProgressService progressService = new ProgressService();
    private final ReflectionService reflectionService = new ReflectionService(flashcardService, progressService);

    @BeforeEach
    void resetDailyReflectionXpCap() {
        // Reflection XP is capped per calendar day via Preferences (see ProgressService), which
        // persists across test runs on the same machine. Clearing it here makes the XP assertions
        // deterministic regardless of what ran earlier today.
        Preferences preferences = Preferences.userNodeForPackage(ProgressService.class);
        preferences.remove("reflectionXpDate");
        preferences.remove("reflectionXpTotal");
    }

    private Deck freshDeck() {
        return deckService.createDeck("Reflection Test " + UUID.randomUUID(), "Scratch deck for ReflectionServiceTest.");
    }

    @Test
    void bugReflectionGeneratesFourIndependentlyAnswerableCards() {
        ReflectionDraft draft = reflectionService.generateBugReflection(
                "500 on checkout", "Null customer id reached the payment call", "Added a null guard before charging",
                "Added a unit test for the null-customer-id path");

        assertEquals(ReflectionType.BUG, draft.getType());
        List<GeneratedCard> cards = draft.getCards();
        assertEquals(4, cards.size());
        assertEquals("What symptom indicated the problem?", cards.get(0).getFront());
        assertEquals("500 on checkout", cards.get(0).getBack());
        assertEquals("What was the root cause?", cards.get(1).getFront());
        assertEquals("Null customer id reached the payment call", cards.get(1).getBack());
        assertEquals("What change fixed it?", cards.get(2).getFront());
        assertEquals("Added a null guard before charging", cards.get(2).getBack());
        assertEquals("What test, constraint, log, or checklist prevents recurrence?", cards.get(3).getFront());
        assertEquals("Added a unit test for the null-customer-id path", cards.get(3).getBack());
    }

    @Test
    void commandReflectionGeneratesFourIndependentlyAnswerableCards() {
        ReflectionDraft draft = reflectionService.generateCommandReflection(
                "git checkout -- file.txt", "That discards changes instead of unstaging them",
                "git restore --staged file.txt", "Use it whenever I mean to unstage, not discard");

        List<GeneratedCard> cards = draft.getCards();
        assertEquals(4, cards.size());
        assertEquals("git checkout -- file.txt", cards.get(0).getBack());
        assertEquals("That discards changes instead of unstaging them", cards.get(1).getBack());
        assertEquals("git restore --staged file.txt", cards.get(2).getBack());
        assertEquals("Use it whenever I mean to unstage, not discard", cards.get(3).getBack());
    }

    @Test
    void missedConceptReflectionGeneratesThreeIndependentlyAnswerableCards() {
        ReflectionDraft draft = reflectionService.generateMissedConceptReflection(
                "database transaction", "A unit of work that commits or rolls back completely",
                "I didn't realize a rollback undoes every statement, not just the last one",
                "Any time I see multiple related writes, ask what should happen if one fails");

        List<GeneratedCard> cards = draft.getCards();
        assertEquals(3, cards.size());
        assertTrue(cards.get(0).getFront().contains("database transaction"));
        assertEquals("A unit of work that commits or rolls back completely", cards.get(0).getBack());
        assertEquals("I didn't realize a rollback undoes every statement, not just the last one", cards.get(1).getBack());
        assertEquals("Any time I see multiple related writes, ask what should happen if one fails", cards.get(2).getBack());
    }

    @Test
    void generationRejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> reflectionService.generateBugReflection("", "cause", "fix", "prevention"));
        assertThrows(IllegalArgumentException.class,
                () -> reflectionService.generateCommandReflection("wrong", null, "right", "reminder"));
        assertThrows(IllegalArgumentException.class,
                () -> reflectionService.generateMissedConceptReflection("concept", "explanation", "   ", "cue"));
    }

    @Test
    void savingAReflectionAwardsXpExactlyOnceRegardlessOfGeneratedCardCount() {
        Deck deck = freshDeck();
        ReflectionDraft draft = reflectionService.generateBugReflection(
                "NPE on checkout", "Null customer id", "Added a null check", "Added a regression test");

        ReflectionSaveResult result = reflectionService.saveReflection(deck.getId(), draft);

        assertEquals(4, result.savedCards().size());
        // If reflection XP were (incorrectly) awarded once per generated card instead of once per
        // reflection, four cards at REFLECTION_CARD_XP each would either total 20 or, once the
        // daily cap kicked in, still exceed a single card's worth. Asserting the exact per-call
        // amount here — not a raw progress.getXp() delta, which also includes unrelated daily-quest
        // completion XP from adding cards — pins down that recordReflectionCardCreated() is called
        // exactly once per saveReflection.
        assertEquals(ProgressService.REFLECTION_CARD_XP, result.xpAwarded());

        Preferences preferences = Preferences.userNodeForPackage(ProgressService.class);
        assertEquals(ProgressService.REFLECTION_CARD_XP, preferences.getInt("reflectionXpTotal", 0),
                "Today's cumulative reflection XP must reflect a single award for this reflection");

        for (Flashcard saved : result.savedCards()) {
            assertEquals(deck.getId(), saved.getDeckId());
            assertEquals(CardType.CONCEPT, saved.getCardType());
        }
    }

    @Test
    void duplicateDetectionFlagsAGeneratedCardMatchingAnExistingCardInTheDeck() {
        Deck deck = freshDeck();
        flashcardService.addCard(deck.getId(), "What was the root cause?", "Already captured this one");

        ReflectionDraft draft = reflectionService.generateBugReflection(
                "Symptom text", "Root cause text", "Fix text", "Prevention text");

        List<GeneratedCard> duplicates = reflectionService.findDuplicates(deck.getId(), draft);
        assertEquals(1, duplicates.size());
        assertEquals("What was the root cause?", duplicates.get(0).getFront());
    }

    @Test
    void duplicateDetectionIsNearExactNotJustCaseSensitiveEquality() {
        Deck deck = freshDeck();
        flashcardService.addCard(deck.getId(), "What   was the ROOT cause?!", "Existing phrasing");

        assertTrue(flashcardService.hasNearDuplicatePromptInDeck(deck.getId(), "What was the root cause?"));

        ReflectionDraft draft = reflectionService.generateBugReflection(
                "Symptom text", "Root cause text", "Fix text", "Prevention text");
        List<GeneratedCard> duplicates = reflectionService.findDuplicates(deck.getId(), draft);
        assertEquals(1, duplicates.size());
    }

    @Test
    void savingSkipsDuplicatesButStillSavesTheRestAndAwardsXpOnce() {
        Deck deck = freshDeck();
        flashcardService.addCard(deck.getId(), "What was the root cause?", "Already captured this one");

        ReflectionDraft draft = reflectionService.generateBugReflection(
                "Symptom text", "Root cause text", "Fix text", "Prevention text");

        ReflectionSaveResult result = reflectionService.saveReflection(deck.getId(), draft);

        assertEquals(3, result.savedCards().size());
        assertEquals(1, result.skippedDuplicates().size());
        assertEquals("What was the root cause?", result.skippedDuplicates().get(0).getFront());
        assertEquals(ProgressService.REFLECTION_CARD_XP, result.xpAwarded());
    }

    @Test
    void savingRejectsAnEmptyDraft() {
        Deck deck = freshDeck();
        ReflectionDraft draft = reflectionService.generateBugReflection("a", "b", "c", "d");
        draft.removeCard(3);
        draft.removeCard(2);
        draft.removeCard(1);
        draft.removeCard(0);

        assertThrows(IllegalArgumentException.class, () -> reflectionService.saveReflection(deck.getId(), draft));
    }
}
