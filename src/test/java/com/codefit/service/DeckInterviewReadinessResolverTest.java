package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DeckInterviewReadinessResolver} against a real database (#178 Slice 2),
 * complementing {@link InterviewReadinessServiceTest}'s database-free aggregation tests.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class DeckInterviewReadinessResolverTest {

    private final DeckRepository deckRepository = new DeckRepository();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final FlashcardService flashcardService = new FlashcardService();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final DeckInterviewReadinessResolver resolver =
            new DeckInterviewReadinessResolver(deckRepository, flashcardService, new MasteryService());

    private InterviewRequirement deckRequirement(String deckName) {
        return InterviewRequirement.available("req", "Requirement", "description", InterviewMaterialType.DECK, deckName);
    }

    @Test
    void unknownDeckNameIsUnmeasurableNotZero() {
        InterviewRequirementReadiness readiness = resolver.resolve(deckRequirement("TEST-FIXTURE: Deck That Does Not Exist"));

        assertFalse(readiness.measurable());
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void deckWithNoCardsYetIsUnmeasurableNotZero() {
        Deck deck = deckRepository.save(new Deck("TEST-FIXTURE: Empty Interview Readiness Deck", "no cards yet"));

        InterviewRequirementReadiness readiness = resolver.resolve(deckRequirement(deck.getName()));

        assertFalse(readiness.measurable());
        assertEquals(null, readiness.scorePercent());
    }

    @Test
    void neverReviewedCardIsARealZeroPercentNotUnmeasurable() {
        Deck deck = deckRepository.save(new Deck("TEST-FIXTURE: Fresh Interview Readiness Deck", "one never-reviewed card"));
        flashcardService.addCard(deck.getId(), "TEST-FIXTURE front", "TEST-FIXTURE back");

        InterviewRequirementReadiness readiness = resolver.resolve(deckRequirement(deck.getName()));

        assertTrue(readiness.measurable(), "the deck has a real card, so this is a genuine 0%, not missing data");
        assertEquals(0, readiness.scorePercent());
    }

    @Test
    void resolverReflectsRealDurableMasteryAcrossAMixOfMasteredAndUnreviewedCards() {
        Deck deck = deckRepository.save(new Deck("TEST-FIXTURE: Mixed Mastery Interview Readiness Deck", "one mastered, one fresh"));

        Flashcard masteredCard = flashcardService.addCard(deck.getId(), "TEST-FIXTURE mastered front", "TEST-FIXTURE mastered back");
        masteredCard.setIntervalDays(14);
        masteredCard.setReviewCount(2);
        flashcardRepository.updateSchedule(masteredCard);
        reviewHistoryRepository.save(new ReviewHistory(0, masteredCard.getId(), ReviewRating.GOOD, 7, 14,
                LocalDateTime.now().minusDays(1), true, false, "EXACT", "answer", 4000, false, "session-1"));
        reviewHistoryRepository.save(new ReviewHistory(0, masteredCard.getId(), ReviewRating.GOOD, 3, 7,
                LocalDateTime.now(), true, false, "EXACT", "answer", 4000, false, "session-2"));

        flashcardService.addCard(deck.getId(), "TEST-FIXTURE unreviewed front", "TEST-FIXTURE unreviewed back");

        InterviewRequirementReadiness readiness = resolver.resolve(deckRequirement(deck.getName()));

        assertTrue(readiness.measurable());
        assertEquals(50, readiness.scorePercent(), "1 of 2 cards durably mastered via MasteryService's real evaluate() logic");
        assertEquals(InterviewMaterialType.DECK, readiness.sourceType());
    }
}
