package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatabaseInternalsPackServiceTest {

    @Test
    void packContainsFiveModulesWithFortyUniqueCardsEach() {
        assertEquals(5, DatabaseInternalsPackService.PACK_DECKS.length);

        Set<String> promptsAcrossPack = new HashSet<>();
        int totalCards = 0;

        for (DatabaseInternalsPackService.PackDeck deck : DatabaseInternalsPackService.PACK_DECKS) {
            InputStream input = DatabaseInternalsPackService.class.getResourceAsStream(deck.resourcePath());
            assertNotNull(input, () -> "missing resource " + deck.resourcePath());

            List<DatabaseInternalsPackService.BundledCard> cards =
                    DatabaseInternalsPackService.parseCards(input, deck.resourcePath());
            assertEquals(40, cards.size(), () -> deck.name() + " should contain exactly 40 cards");

            for (DatabaseInternalsPackService.BundledCard card : cards) {
                assertFalse(card.front().isBlank());
                assertFalse(card.back().isBlank());
                assertFalse(card.skillCategory().isBlank());
                promptsAcrossPack.add(card.front().strip().toLowerCase());
            }
            totalCards += cards.size();
        }

        assertEquals(200, totalCards);
        assertEquals(200, promptsAcrossPack.size(), "prompts should be unique across the complete pack");
    }

    @Test
    void reinstallSummaryExplainsDuplicateSkipping() {
        DatabaseInternalsPackService.InstallSummary summary =
                new DatabaseInternalsPackService.InstallSummary(0, 0, 200);

        assertEquals("Database Internals installed: 0 decks created, 0 cards imported, 200 duplicates skipped.",
                summary.message());
    }
}
