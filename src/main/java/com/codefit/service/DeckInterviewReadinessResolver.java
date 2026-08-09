package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.repository.DeckRepository;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a {@code DECK} requirement using {@link MasteryService}'s existing durable-mastery
 * percentage - the same figure {@link SyllabusService} and {@link TrainingPathService} already
 * surface as a deck/module's progress - rather than inventing a second mastery calculation. Deck
 * names are matched case-insensitively, the same way {@code TrainingPath.TrainingPathModule} matches
 * decks by name.
 */
class DeckInterviewReadinessResolver implements InterviewRequirementReadinessResolver {
    private final DeckRepository deckRepository;
    private final FlashcardService flashcardService;
    private final MasteryService masteryService;

    DeckInterviewReadinessResolver() {
        this(new DeckRepository(), new FlashcardService(), new MasteryService());
    }

    DeckInterviewReadinessResolver(DeckRepository deckRepository, FlashcardService flashcardService,
                                   MasteryService masteryService) {
        this.deckRepository = deckRepository;
        this.flashcardService = flashcardService;
        this.masteryService = masteryService;
    }

    @Override
    public boolean supports(InterviewMaterialType type) {
        return type == InterviewMaterialType.DECK;
    }

    @Override
    public InterviewRequirementReadiness resolve(InterviewRequirement requirement) {
        String deckName = requirement.getReference().key();
        Optional<Deck> deck = deckRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(deckName))
                .findFirst();
        if (deck.isEmpty()) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.DECK,
                    "No deck named '" + deckName + "' exists yet.");
        }

        List<Flashcard> cards = flashcardService.getCardsForDeck(deck.get().getId());
        if (cards.isEmpty()) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.DECK,
                    "Deck '" + deckName + "' has no cards yet.");
        }

        double masteredPercent = masteryService.summarize(cards).masteredPercent();
        return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.DECK, masteredPercent,
                "Durable mastery across " + cards.size() + " card(s) in '" + deckName + "'.");
    }
}
