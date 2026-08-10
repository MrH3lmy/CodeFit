package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for users who installed the original 20-card-per-deck RJ pack. */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class RevolutInterviewContentPackUpgradeTest {

    @Test
    void syncUpgradesOriginalCoreOnlyInstallByAddingExactlyTwoHundredTenCards() {
        RevolutInterviewContentPackService packService = new RevolutInterviewContentPackService();
        DeckService deckService = new DeckService();
        FlashcardService flashcardService = new FlashcardService();

        int originalCoreCards = 0;
        for (RevolutInterviewContentPackService.PackDeck definition : RevolutInterviewContentPackService.PACK_DECKS) {
            Deck deck = deckService.createDeck(definition.name(), definition.description());
            for (DatabaseInternalsPackService.BundledCard card : packService.loadCards(definition.resourcePath())) {
                flashcardService.addCard(
                        deck.getId(),
                        card.front(),
                        card.back(),
                        card.cardType(),
                        card.acceptedAnswers(),
                        card.validationMode(),
                        null,
                        card.hint(),
                        card.timeLimitSeconds(),
                        card.skillCategory());
                originalCoreCards++;
            }
        }
        assertEquals(140, originalCoreCards);

        RevolutInterviewContentPackService.InstallSummary upgrade = packService.install();
        assertEquals(0, upgrade.decksCreated());
        assertEquals(210, upgrade.cardsImported());
        assertEquals(140, upgrade.duplicatesSkipped());

        RevolutInterviewContentPackService.InstallSummary repeat = packService.install();
        assertEquals(0, repeat.decksCreated());
        assertEquals(0, repeat.cardsImported());
        assertEquals(350, repeat.duplicatesSkipped());
    }
}
