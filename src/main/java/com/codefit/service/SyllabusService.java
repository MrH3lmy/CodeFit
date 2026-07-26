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
        return getModules(trainingPathService.getJavaBackendPath());
    }

    public List<SyllabusModule> getAdvancedBackendEngineeringModules() {
        return getModules(trainingPathService.getAdvancedBackendEngineeringPath());
    }

    public List<SyllabusModule> getDatabaseInternalsModules() {
        return getModules(trainingPathService.getDatabaseInternalsPath());
    }

    /** All registered training paths' modules, in path-registration order, each internally ordered by module number. */
    public List<SyllabusModule> getAllTrainingPathModules() {
        return trainingPathService.getTrainingPaths().stream()
                .flatMap(path -> getModules(path).stream())
                .toList();
    }

    public List<SyllabusModule> getModules(TrainingPath path) {
        List<Deck> decks = deckRepository.findAll();
        return path.getModules().stream()
                .map(definition -> toModule(path.getName(), definition, decks))
                .sorted(Comparator.comparingInt(SyllabusModule::getModuleNumber))
                .toList();
    }

    private SyllabusModule toModule(String pathName, TrainingPath.TrainingPathModule definition, List<Deck> decks) {
        List<Deck> matchingDecks = decks.stream()
                .filter(candidate -> definition.getDeckNames().stream()
                        .anyMatch(deckName -> deckName.equalsIgnoreCase(candidate.getName())))
                .toList();
        if (matchingDecks.isEmpty()) {
            return new SyllabusModule(pathName, definition.getOrder(), definition.getTitle(), definition.getLearningObjective(),
                    0, definition.getDeckName(), 0, 0, 0, 0);
        }

        List<Flashcard> cards = matchingDecks.stream()
                .flatMap(deck -> flashcardRepository.findByDeckId(deck.getId()).stream())
                .toList();
        MasteryService.MasteryBreakdown breakdown = masteryService.summarize(cards);
        Deck representativeDeck = matchingDecks.get(0);
        String displayDeckName = matchingDecks.size() == 1
                ? representativeDeck.getName()
                : representativeDeck.getName() + " (+" + (matchingDecks.size() - 1) + " more)";
        return new SyllabusModule(pathName, definition.getOrder(), definition.getTitle(), definition.getLearningObjective(),
                representativeDeck.getId(), displayDeckName, cards.size(), breakdown.seenCards(), breakdown.learningCards(),
                breakdown.masteredCards());
    }
}
