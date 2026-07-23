package com.codefit.service;

import com.codefit.model.Flashcard;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Drives a single review session's active queue, including automatic same-session relearning:
 * a card rated Again/Hard is reinserted a few cards ahead instead of only being offered again
 * through a separate post-session pass. A per-card retry limit stops a stubborn card from
 * looping the session forever.
 */
public class SessionQueue {
    private final LinkedList<Flashcard> queue = new LinkedList<>();
    private final Map<Long, Integer> retryCounts = new HashMap<>();
    private final int retryLimit;

    public SessionQueue(List<Flashcard> initialCards, int retryLimit) {
        this.queue.addAll(initialCards);
        this.retryLimit = retryLimit;
    }

    public boolean hasNext() {
        return !queue.isEmpty();
    }

    public Flashcard poll() {
        return queue.poll();
    }

    public int remainingSize() {
        return queue.size();
    }

    /**
     * Reinserts {@code card} roughly {@code offset} other cards ahead. If fewer than
     * {@code offset} cards remain, it's placed at the end rather than sooner. It never becomes
     * the immediate next card while other cards remain, unless the queue is currently empty.
     *
     * @return false if the card has exhausted its same-session retry limit and was not requeued
     */
    public boolean requeue(Flashcard card, int offset) {
        int attempts = retryCounts.merge(card.getId(), 1, Integer::sum);
        if (attempts > retryLimit) {
            return false;
        }
        int insertPosition = queue.isEmpty() ? 0 : Math.max(1, Math.min(offset, queue.size()));
        queue.add(insertPosition, card);
        return true;
    }

    public int retryCount(long cardId) {
        return retryCounts.getOrDefault(cardId, 0);
    }
}
