package com.codefit.service;

import com.codefit.model.Flashcard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interleaves several already-prioritized candidate lists into one queue using weighted
 * round-robin, so the session mix approximates target ratios (e.g. 60% highest forgetting risk,
 * 20% weakest skill, 10% recently failed, 10% new/stretch) while low-ratio buckets accumulate
 * "credit" slowly and only surface once the higher-priority buckets have contributed their share.
 * A card already selected from an earlier bucket is skipped everywhere else it appears.
 */
public class AdaptiveQueueMixer {

    public record Bucket(String label, List<Flashcard> candidates, double targetRatio) {
    }

    public record MixedCard(Flashcard card, String bucketLabel) {
    }

    public List<MixedCard> mix(List<Bucket> buckets, int maxCards) {
        List<MixedCard> result = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        int[] cursors = new int[buckets.size()];
        double[] credit = new double[buckets.size()];

        // A round with a low-ratio bucket can legitimately add nothing yet (its credit hasn't
        // reached 1.0), so termination must check bucket exhaustion, not "did this round add
        // anything" — otherwise a 0.1-ratio bucket would wrongly stop the whole mix on round one.
        while (result.size() < maxCards && !allBucketsExhausted(buckets, cursors)) {
            for (int i = 0; i < buckets.size(); i++) {
                Bucket bucket = buckets.get(i);
                credit[i] += bucket.targetRatio();
                while (credit[i] >= 1.0 && result.size() < maxCards) {
                    credit[i] -= 1.0;
                    Flashcard next = nextUnselected(bucket.candidates(), cursors, i, selectedIds);
                    if (next == null) {
                        break;
                    }
                    result.add(new MixedCard(next, bucket.label()));
                    selectedIds.add(next.getId());
                }
            }
        }
        return result;
    }

    private boolean allBucketsExhausted(List<Bucket> buckets, int[] cursors) {
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).targetRatio() > 0 && cursors[i] < buckets.get(i).candidates().size()) {
                return false;
            }
        }
        return true;
    }

    private Flashcard nextUnselected(List<Flashcard> candidates, int[] cursors, int bucketIndex, Set<Long> selectedIds) {
        while (cursors[bucketIndex] < candidates.size()) {
            Flashcard candidate = candidates.get(cursors[bucketIndex]);
            cursors[bucketIndex]++;
            if (!selectedIds.contains(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }
}
