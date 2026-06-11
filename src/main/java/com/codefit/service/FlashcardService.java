package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.repository.FlashcardRepository;

import java.util.List;

public class FlashcardService {
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();

    public List<Flashcard> getAllCards() {
        return flashcardRepository.findAll();
    }

    public List<Flashcard> getCardsForDeck(long deckId) {
        return flashcardRepository.findByDeckId(deckId);
    }

    public List<Flashcard> getDueCards() {
        return flashcardRepository.findDueCards();
    }

    public Flashcard addCard(long deckId, String front, String back) {
        if (deckId <= 0) {
            throw new IllegalArgumentException("Choose a deck before adding a card.");
        }
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new IllegalArgumentException("Both prompt and answer are required.");
        }
        return flashcardRepository.save(new Flashcard(deckId, front.trim(), back.trim()));
    }

    public int countAllCards() {
        return flashcardRepository.countAll();
    }

    public int countDueCards() {
        return flashcardRepository.countDue();
    }
}
