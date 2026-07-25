package com.codefit.model;

/**
 * One atomic card produced by splitting a work reflection (#102), still in draft form inside a
 * {@link ReflectionDraft}. Deliberately not a {@link Flashcard}: nothing here is persisted, or
 * even a candidate for persistence, until the learner reviews the preview and saves it — at which
 * point {@code ReflectionService.saveReflection} turns each surviving card into a real Flashcard.
 */
public class GeneratedCard {
    private String front;
    private String back;
    private CardType cardType;

    public GeneratedCard(String front, String back, CardType cardType) {
        this.front = front;
        this.back = back;
        this.cardType = cardType;
    }

    public String getFront() {
        return front;
    }

    public void setFront(String front) {
        this.front = front;
    }

    public String getBack() {
        return back;
    }

    public void setBack(String back) {
        this.back = back;
    }

    public CardType getCardType() {
        return cardType;
    }

    public void setCardType(CardType cardType) {
        this.cardType = cardType;
    }
}
