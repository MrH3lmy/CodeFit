package com.codefit.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-progress atomic-card breakdown of one work reflection (#102): the workflow it came from, plus
 * the mutable list of {@link GeneratedCard} drafts the learner previews before saving. Editing,
 * removing, and merging only go through the methods below so a preview screen can't drift from
 * what {@code ReflectionService.saveReflection} will actually persist.
 */
public class ReflectionDraft {
    private final ReflectionType type;
    private final List<GeneratedCard> cards;

    public ReflectionDraft(ReflectionType type, List<GeneratedCard> cards) {
        this.type = type;
        this.cards = new ArrayList<>(cards);
    }

    public ReflectionType getType() {
        return type;
    }

    public List<GeneratedCard> getCards() {
        return Collections.unmodifiableList(cards);
    }

    public int size() {
        return cards.size();
    }

    public void removeCard(int index) {
        validateIndex(index);
        cards.remove(index);
    }

    public void editCard(int index, String front, String back) {
        validateIndex(index);
        GeneratedCard card = cards.get(index);
        card.setFront(front);
        card.setBack(back);
    }

    /** Combines two generated cards the learner decides test the same recalled fact: their prompts
     *  and answers are joined into the card at {@code index}, and the card at {@code otherIndex} is
     *  dropped. */
    public void mergeCards(int index, int otherIndex) {
        validateIndex(index);
        validateIndex(otherIndex);
        if (index == otherIndex) {
            throw new IllegalArgumentException("Choose two different cards to merge.");
        }
        GeneratedCard first = cards.get(index);
        GeneratedCard second = cards.get(otherIndex);
        first.setFront(first.getFront() + " / " + second.getFront());
        first.setBack(first.getBack() + "\n" + second.getBack());
        cards.remove(otherIndex);
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= cards.size()) {
            throw new IndexOutOfBoundsException("No generated card at index " + index);
        }
    }
}
