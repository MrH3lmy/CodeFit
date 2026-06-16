package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.repository.FlashcardRepository;

import java.util.List;
import java.util.Optional;

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

    public Optional<Flashcard> getCardById(long id) {
        return flashcardRepository.findById(id);
    }

    public Flashcard addCard(long deckId, String front, String back) {
        return addCard(deckId, front, back, CardType.RECALL, back, ValidationMode.CASE_INSENSITIVE, null, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput) {
        return addCard(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                             Integer timeLimitSeconds) {
        if (deckId <= 0) {
            throw new IllegalArgumentException("Choose a deck before adding a card.");
        }
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new IllegalArgumentException("Both prompt and answer are required.");
        }
        String answers = acceptedAnswers == null || acceptedAnswers.isBlank() ? back : acceptedAnswers;
        return flashcardRepository.save(new Flashcard(deckId, front.trim(), back.trim(), cardType,
                answers.trim(), validationMode, simulatedOutput == null || simulatedOutput.isBlank() ? null : simulatedOutput.trim(),
                timeLimitSeconds));
    }

    public int countAllCards() {
        return flashcardRepository.countAll();
    }

    public int countDueCards() {
        return flashcardRepository.countDue();
    }
}
