package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.service.AdaptiveQueueMixer.Bucket;
import com.codefit.service.AdaptiveQueueMixer.MixedCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveQueueMixerTest {

    private final AdaptiveQueueMixer mixer = new AdaptiveQueueMixer();

    private Flashcard card(long id) {
        Flashcard flashcard = new Flashcard(1, "front " + id, "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(id);
        return flashcard;
    }

    private List<Flashcard> cards(long... ids) {
        List<Flashcard> result = new ArrayList<>();
        for (long id : ids) {
            result.add(card(id));
        }
        return result;
    }

    @Test
    void highRatioBucketDominatesEarlyPositionsOverLowRatioBucket() {
        Bucket highRisk = new Bucket("high risk", cards(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0.6);
        Bucket stretch = new Bucket("stretch", cards(101, 102), 0.1);

        List<MixedCard> mixed = mixer.mix(List.of(highRisk, stretch), 20);

        // Weight 0.1 needs 10 rounds of +0.1 credit to reach 1.0, so the stretch bucket's first
        // card should not appear until several high-risk cards have already been placed.
        int firstStretchIndex = indexOfFirstFromBucket(mixed, "stretch");
        assertTrue(firstStretchIndex >= 5, "expected the low-ratio bucket to be deferred, was at index " + firstStretchIndex);
    }

    @Test
    void neverDuplicatesACardThatAppearsInMultipleBuckets() {
        Flashcard shared = card(1);
        Bucket bucketA = new Bucket("A", List.of(shared, card(2)), 0.6);
        Bucket bucketB = new Bucket("B", List.of(shared, card(3)), 0.4);

        List<MixedCard> mixed = mixer.mix(List.of(bucketA, bucketB), 10);

        long sharedCount = mixed.stream().filter(m -> m.card().getId() == 1).count();
        assertEquals(1, sharedCount);
        assertEquals(3, mixed.size());
    }

    @Test
    void stopsWhenAllBucketsAreExhaustedEvenBelowMaxCards() {
        Bucket small = new Bucket("small", cards(1, 2), 0.5);
        Bucket alsoSmall = new Bucket("also small", cards(3), 0.5);

        List<MixedCard> mixed = mixer.mix(List.of(small, alsoSmall), 100);

        assertEquals(3, mixed.size());
    }

    @Test
    void respectsMaxCardsCap() {
        Bucket bucket = new Bucket("only", cards(1, 2, 3, 4, 5), 1.0);
        List<MixedCard> mixed = mixer.mix(List.of(bucket), 2);
        assertEquals(2, mixed.size());
    }

    @Test
    void emptyBucketsProduceEmptyResult() {
        List<MixedCard> mixed = mixer.mix(List.of(new Bucket("empty", List.of(), 1.0)), 10);
        assertTrue(mixed.isEmpty());
    }

    private int indexOfFirstFromBucket(List<MixedCard> mixed, String label) {
        for (int i = 0; i < mixed.size(); i++) {
            if (mixed.get(i).bucketLabel().equals(label)) {
                return i;
            }
        }
        return -1;
    }
}
