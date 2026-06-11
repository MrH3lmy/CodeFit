package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.repository.DeckRepository;

import java.util.List;

public class DeckService {
    private final DeckRepository deckRepository = new DeckRepository();

    public List<Deck> getDecks() {
        return deckRepository.findAll();
    }

    public Deck createDeck(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Deck name is required.");
        }
        return deckRepository.save(new Deck(name.trim(), description == null || description.isBlank() ? "Custom training deck." : description.trim()));
    }
}
