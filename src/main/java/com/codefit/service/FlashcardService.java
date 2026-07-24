package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.repository.FlashcardRepository;

import java.util.List;
import java.util.Optional;

public class FlashcardService {
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final DailyQuestService dailyQuestService = new DailyQuestService();

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

    public boolean cardExistsInDeck(long deckId, String front) {
        return flashcardRepository.existsByDeckIdAndFront(deckId, front);
    }

    public Flashcard addCard(long deckId, String front, String back) {
        return addCard(deckId, front, back, CardType.RECALL, back, ValidationMode.CASE_INSENSITIVE, null, null, null, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput) {
        return addCard(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, null, null, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                             Integer timeLimitSeconds) {
        return addCard(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, null, timeLimitSeconds, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                             String hint, Integer timeLimitSeconds) {
        return addCard(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, hint, timeLimitSeconds, null);
    }

    public Flashcard addCard(long deckId, String front, String back, CardType cardType,
                             String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                             String hint, Integer timeLimitSeconds, String skillCategory) {
        if (deckId <= 0) {
            throw new IllegalArgumentException("Choose a deck before adding a card.");
        }
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new IllegalArgumentException("Both prompt and answer are required.");
        }
        String rawAnswers = acceptedAnswers == null || acceptedAnswers.isBlank() ? back : acceptedAnswers;
        String answers = AcceptedAnswerCodec.normalize(rawAnswers);
        Flashcard flashcard = new Flashcard(deckId, front.trim(), back.trim(), cardType,
                answers, validationMode, simulatedOutput == null || simulatedOutput.isBlank() ? null : simulatedOutput.trim(),
                hint == null || hint.isBlank() ? null : hint.trim(), timeLimitSeconds);
        flashcard.setSkillCategory(skillCategory);
        Flashcard savedCard = flashcardRepository.save(flashcard);
        dailyQuestService.recordCardAdded();
        return savedCard;
    }

    /** Updates an existing card's editable content. Scheduling state (due date, interval, ease,
     *  review count, card state) is preserved unchanged. */
    public Flashcard updateCard(long cardId, String front, String back, CardType cardType,
                                 String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                                 String hint, Integer timeLimitSeconds, String skillCategory) {
        Flashcard existing = flashcardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found."));
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new IllegalArgumentException("Both prompt and answer are required.");
        }
        String rawAnswers = acceptedAnswers == null || acceptedAnswers.isBlank() ? back : acceptedAnswers;
        String answers = AcceptedAnswerCodec.normalize(rawAnswers);

        existing.setFront(front.trim());
        existing.setBack(back.trim());
        existing.setCardType(cardType);
        existing.setAcceptedAnswers(answers);
        existing.setValidationMode(validationMode);
        existing.setSimulatedOutput(simulatedOutput == null || simulatedOutput.isBlank() ? null : simulatedOutput.trim());
        existing.setHint(hint == null || hint.isBlank() ? null : hint.trim());
        existing.setTimeLimitSeconds(timeLimitSeconds);
        existing.setSkillCategory(skillCategory);
        flashcardRepository.updateContent(existing);
        return existing;
    }

    public int countAllCards() {
        return flashcardRepository.countAll();
    }

    public int countDueCards() {
        return flashcardRepository.countDue();
    }

    public int countNewCards() {
        return flashcardRepository.countNew();
    }
}
