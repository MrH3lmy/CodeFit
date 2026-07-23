package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.SyllabusModule;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.model.TrainingPath;

import java.util.Comparator;
import java.util.List;

public class SyllabusService {
    private final DeckRepository deckRepository = new DeckRepository();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final TrainingPathService trainingPathService = new TrainingPathService();
    private final MasteryService masteryService = new MasteryService();

    public List<SyllabusModule> getJavaBackendModules() {
        List<Deck> decks = deckRepository.findAll();
        return trainingPathService.getJavaBackendPath().getModules().stream()
                .map(definition -> toModule(definition, decks))
                .sorted(Comparator.comparingInt(SyllabusModule::getModuleNumber))
                .toList();
    }

    private SyllabusModule toModule(TrainingPath.TrainingPathModule definition, List<Deck> decks) {
        Deck deck = decks.stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(definition.getDeckName()))
                .findFirst()
                .orElse(null);
        if (deck == null) {
            return new SyllabusModule(definition.getOrder(), definition.getTitle(), definition.getLearningObjective(),
                    0, definition.getDeckName(), 0, 0, 0, 0);
        }

        List<Flashcard> cards = flashcardRepository.findByDeckId(deck.getId());
        MasteryService.MasteryBreakdown breakdown = masteryService.summarize(cards);
        return new SyllabusModule(definition.getOrder(), definition.getTitle(), definition.getLearningObjective(),
                deck.getId(), deck.getName(), cards.size(), breakdown.seenCards(), breakdown.learningCards(),
                breakdown.masteredCards());
    }
}
