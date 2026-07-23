package com.codefit.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class Flashcard {
    private long id;
    private long deckId;
    private String front;
    private String back;
    private CardType cardType;
    private String acceptedAnswers;
    private ValidationMode validationMode;
    private String simulatedOutput;
    private String hint;
    private String skillCategory;
    private LocalDate dueDate;
    private int intervalDays;
    private double easeFactor;
    private int reviewCount;
    private LocalDateTime createdAt;
    private Integer timeLimitSeconds;
    private CardState cardState = CardState.NEW;
    private LocalDateTime introducedAt;

    public Flashcard(long id, long deckId, String front, String back, LocalDate dueDate,
                     int intervalDays, double easeFactor, int reviewCount, LocalDateTime createdAt) {
        this(id, deckId, front, back, CardType.RECALL, back, ValidationMode.CASE_INSENSITIVE, null, null,
                dueDate, intervalDays, easeFactor, reviewCount, createdAt, null);
    }

    public Flashcard(long id, long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput, String hint,
                     LocalDate dueDate, int intervalDays, double easeFactor, int reviewCount,
                     LocalDateTime createdAt) {
        this(id, deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, hint,
                dueDate, intervalDays, easeFactor, reviewCount, createdAt, null);
    }

    public Flashcard(long id, long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput, String hint,
                     LocalDate dueDate, int intervalDays, double easeFactor, int reviewCount,
                     LocalDateTime createdAt, Integer timeLimitSeconds) {
        this(id, deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, hint, "General",
                dueDate, intervalDays, easeFactor, reviewCount, createdAt, timeLimitSeconds);
    }

    public Flashcard(long id, long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput, String hint, String skillCategory,
                     LocalDate dueDate, int intervalDays, double easeFactor, int reviewCount,
                     LocalDateTime createdAt, Integer timeLimitSeconds) {
        this.id = id;
        this.deckId = deckId;
        this.front = front;
        this.back = back;
        this.cardType = cardType == null ? CardType.RECALL : cardType;
        this.acceptedAnswers = acceptedAnswers == null || acceptedAnswers.isBlank() ? back : acceptedAnswers;
        this.validationMode = validationMode == null ? ValidationMode.CASE_INSENSITIVE : validationMode;
        this.simulatedOutput = simulatedOutput;
        this.hint = hint;
        this.skillCategory = normalizeSkillCategory(skillCategory);
        this.dueDate = dueDate;
        this.intervalDays = intervalDays;
        this.easeFactor = easeFactor;
        this.reviewCount = reviewCount;
        this.createdAt = createdAt;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public Flashcard(long deckId, String front, String back) {
        this(deckId, front, back, CardType.RECALL, back, ValidationMode.CASE_INSENSITIVE, null, null);
    }

    public Flashcard(long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput) {
        this(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, null, null);
    }

    public Flashcard(long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                     Integer timeLimitSeconds) {
        this(deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, null, timeLimitSeconds);
    }

    public Flashcard(long deckId, String front, String back, CardType cardType,
                     String acceptedAnswers, ValidationMode validationMode, String simulatedOutput, String hint,
                     Integer timeLimitSeconds) {
        this(0, deckId, front, back, cardType, acceptedAnswers, validationMode, simulatedOutput, hint,
                LocalDate.now(), 0, 2.5, 0, LocalDateTime.now(), timeLimitSeconds);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getDeckId() { return deckId; }
    public void setDeckId(long deckId) { this.deckId = deckId; }
    public String getFront() { return front; }
    public void setFront(String front) { this.front = front; }
    public String getBack() { return back; }
    public void setBack(String back) { this.back = back; }
    public CardType getCardType() { return cardType; }
    public void setCardType(CardType cardType) { this.cardType = cardType; }
    public String getAcceptedAnswers() { return acceptedAnswers; }
    public void setAcceptedAnswers(String acceptedAnswers) { this.acceptedAnswers = acceptedAnswers; }
    public ValidationMode getValidationMode() { return validationMode; }
    public void setValidationMode(ValidationMode validationMode) { this.validationMode = validationMode; }
    public String getSimulatedOutput() { return simulatedOutput; }
    public void setSimulatedOutput(String simulatedOutput) { this.simulatedOutput = simulatedOutput; }
    public String getHint() { return hint; }
    public void setHint(String hint) { this.hint = hint; }
    public String getSkillCategory() { return skillCategory; }
    public void setSkillCategory(String skillCategory) { this.skillCategory = normalizeSkillCategory(skillCategory); }
    public boolean hasHint() { return hint != null && !hint.isBlank(); }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }
    public double getEaseFactor() { return easeFactor; }
    public void setEaseFactor(double easeFactor) { this.easeFactor = easeFactor; }
    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getTimeLimitSeconds() { return timeLimitSeconds; }
    public void setTimeLimitSeconds(Integer timeLimitSeconds) { this.timeLimitSeconds = timeLimitSeconds; }
    public CardState getCardState() { return cardState; }
    public void setCardState(CardState cardState) { this.cardState = cardState == null ? CardState.NEW : cardState; }
    public LocalDateTime getIntroducedAt() { return introducedAt; }
    public void setIntroducedAt(LocalDateTime introducedAt) { this.introducedAt = introducedAt; }

    private String normalizeSkillCategory(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }
}
