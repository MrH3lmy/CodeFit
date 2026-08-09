package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class RevolutInterviewContentPackServiceTest {

    private final RevolutInterviewContentPackService packService = new RevolutInterviewContentPackService();

    @Test
    void bundledPackShipsSevenDistinctDecksWithTwentyUniqueInterviewCardsEach() {
        assertEquals(7, RevolutInterviewContentPackService.PACK_DECKS.length);
        assertEquals(7, new HashSet<>(packService.deckNames()).size());

        Set<String> promptsAcrossPack = new HashSet<>();
        int totalCards = 0;
        for (RevolutInterviewContentPackService.PackDeck deck : RevolutInterviewContentPackService.PACK_DECKS) {
            List<DatabaseInternalsPackService.BundledCard> cards = packService.loadCards(deck.resourcePath());
            assertEquals(20, cards.size(), () -> deck.name() + " should contain exactly 20 focused interview cards");
            for (DatabaseInternalsPackService.BundledCard card : cards) {
                assertFalse(card.front().isBlank());
                assertFalse(card.back().isBlank());
                assertFalse(AcceptedAnswerCodec.decode(card.acceptedAnswers()).isEmpty());
                assertNotNull(card.cardType());
                assertNotNull(card.validationMode());
                assertNotNull(card.hint());
                assertFalse(card.hint().isBlank());
                assertNotNull(card.skillCategory());
                assertFalse(card.skillCategory().isBlank());
                assertNotNull(card.timeLimitSeconds());
                assertTrue(card.timeLimitSeconds() > 0);
                assertTrue(promptsAcrossPack.add(card.front().strip().toLowerCase()),
                        () -> "duplicate prompt across RJ pack: " + card.front());
                totalCards++;
            }
        }
        assertEquals(140, totalCards);
        assertEquals(140, promptsAcrossPack.size());
    }

    @Test
    void installIsIdempotentAndCreatesExactlyTheProfileBackedDecks() {
        RevolutInterviewContentPackService.InstallSummary first = packService.install();
        assertEquals(7, first.decksCreated());
        assertEquals(140, first.cardsImported());
        assertEquals(0, first.duplicatesSkipped());

        Set<String> installedRjNames = new DeckService().getDecks().stream()
                .map(Deck::getName)
                .filter(name -> name.startsWith("RJ "))
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(packService.deckNames()), installedRjNames);

        Set<String> profileRjDecks = new InterviewProfileService().getRevolutJavaProfile().getDomains().stream()
                .flatMap(domain -> domain.getRequirements().stream())
                .filter(requirement -> requirement.getId().startsWith("rj-"))
                .peek(requirement -> assertTrue(requirement.isAvailable()))
                .map(InterviewRequirement::getReference)
                .peek(reference -> assertEquals(InterviewMaterialType.DECK, reference.type()))
                .map(reference -> reference.key())
                .collect(Collectors.toSet());
        assertEquals(installedRjNames, profileRjDecks);

        RevolutInterviewContentPackService.InstallSummary second = packService.install();
        assertEquals(0, second.decksCreated());
        assertEquals(0, second.cardsImported());
        assertEquals(140, second.duplicatesSkipped());
    }

    @Test
    void installingThePackTurnsSystemDesignIntoRealMeasuredCriticalEvidence() {
        InterviewDomainReadiness before = domain(new InterviewReadinessService()
                .calculate(RevolutJavaInterviewProfile.ID).orElseThrow(), "system-design");
        assertFalse(before.isMeasured(), "the content pack is explicit and should not be auto-installed into a fresh database");

        packService.install();

        InterviewDomainReadiness after = domain(new InterviewReadinessService()
                .calculate(RevolutJavaInterviewProfile.ID).orElseThrow(), "system-design");
        assertTrue(after.isMeasured());
        assertEquals(0, after.scorePercent(), "newly installed, never-reviewed decks have real zero durable mastery");
        assertEquals(InterviewDomainReadinessStatus.FAIL, after.status());
        assertEquals(2, after.measuredRequirementCount(), "both RJ04 and RJ05 are now real deck evidence");
    }

    private InterviewDomainReadiness domain(InterviewReadinessResult result, String domainId) {
        return result.domains().stream().filter(domain -> domain.domainId().equals(domainId))
                .findFirst().orElseThrow();
    }
}
