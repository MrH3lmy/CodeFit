package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionQueueTest {

    private Flashcard card(long id) {
        Flashcard flashcard = new Flashcard(1, "front " + id, "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(id);
        return flashcard;
    }

    @Test
    void pollsCardsInOriginalOrder() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2), card(3)), 3);
        assertEquals(1, queue.poll().getId());
        assertEquals(2, queue.poll().getId());
        assertEquals(3, queue.poll().getId());
        assertFalse(queue.hasNext());
    }

    @Test
    void requeuedCardDoesNotAppearImmediatelyWhenOthersRemain() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2), card(3), card(4)), 3);
        Flashcard failed = queue.poll(); // card 1
        assertTrue(queue.requeue(failed, 2));

        Flashcard next = queue.poll();
        assertFalse(next.getId() == failed.getId(), "requeued card must not be the very next one while others remain");
    }

    @Test
    void requeuedCardReappearsAfterApproximatelyTheRequestedOffset() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2), card(3), card(4), card(5)), 3);
        Flashcard failed = queue.poll(); // card 1
        queue.requeue(failed, 3);

        // card1 was requeued 3 ahead: expect card2, card3, card4, then card1 again.
        assertEquals(2, queue.poll().getId());
        assertEquals(3, queue.poll().getId());
        assertEquals(4, queue.poll().getId());
        assertEquals(1, queue.poll().getId());
    }

    @Test
    void requeuedCardAppearsImmediatelyWhenNoOtherCardsRemain() {
        SessionQueue queue = new SessionQueue(List.of(card(1)), 3);
        Flashcard failed = queue.poll();
        assertTrue(queue.requeue(failed, 3));

        assertEquals(1, queue.poll().getId());
    }

    @Test
    void offsetLargerThanRemainingQueueAppendsAtEnd() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2)), 3);
        Flashcard failed = queue.poll(); // card 1
        queue.requeue(failed, 10);

        assertEquals(2, queue.poll().getId());
        assertEquals(1, queue.poll().getId());
    }

    @Test
    void retryLimitStopsRequeueingAfterConfiguredAttempts() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2)), 2);
        Flashcard failing = card(1);

        assertTrue(queue.requeue(failing, 1));
        assertTrue(queue.requeue(failing, 1));
        assertFalse(queue.requeue(failing, 1), "third retry should exceed the limit of 2");
    }

    @Test
    void retryCountTracksAttemptsPerCard() {
        SessionQueue queue = new SessionQueue(List.of(card(1)), 5);
        Flashcard failing = card(1);
        assertEquals(0, queue.retryCount(1));
        queue.requeue(failing, 1);
        assertEquals(1, queue.retryCount(1));
        queue.requeue(failing, 1);
        assertEquals(2, queue.retryCount(1));
    }

    @Test
    void remainingSizeReflectsRequeues() {
        SessionQueue queue = new SessionQueue(List.of(card(1), card(2)), 3);
        assertEquals(2, queue.remainingSize());
        Flashcard failed = queue.poll();
        assertEquals(1, queue.remainingSize());
        queue.requeue(failed, 1);
        assertEquals(2, queue.remainingSize());
    }
}
