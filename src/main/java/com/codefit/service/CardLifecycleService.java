package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.service.MasteryService.CardMasteryState;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Centralizes card lifecycle transitions so every review path (normal, boss battle, relearning
 * retries) applies the same rules instead of scattering ad hoc state changes across services.
 */
public class CardLifecycleService {

    /** Bounds for a diagnostic graduation interval (see {@link #graduate(Flashcard, int)}); the
     *  caller's requested interval is clamped into this range rather than trusted outright. */
    public static final int MIN_GRADUATION_INTERVAL_DAYS = 30;
    public static final int MAX_GRADUATION_INTERVAL_DAYS = 60;
    public static final int DEFAULT_GRADUATION_INTERVAL_DAYS = 45;

    /** Scheduling values a rewritten card restarts from in {@link #resetForRewrite}, matching what
     *  a freshly created card gets (see Flashcard's default-field initializers). */
    public static final double DEFAULT_EASE_FACTOR = 2.5;

    /** Default evidence thresholds for {@link #isLeech}; see {@link LeechThresholds} for what each
     *  one means. Configurable per {@link #applyReviewOutcome} caller rather than hardwired, since
     *  the issue calls for leech detection to be configurable. */
    public static final LeechThresholds DEFAULT_LEECH_THRESHOLDS =
            new LeechThresholds(5, 3, 4, 0.6, 3, 2.0, 2, 3);

    /**
     * Applies the outcome of a single review to a card's lifecycle state and records when a card
     * was first introduced (left NEW). Mutates and returns the given card; callers persist it.
     * Leech detection is skipped (no review history to evaluate); use the four-argument overload
     * to enable it.
     */
    public Flashcard applyReviewOutcome(Flashcard card, ReviewRating rating, CardMasteryState masteryState) {
        return applyReviewOutcome(card, rating, masteryState, List.of());
    }

    /**
     * @param recentReviewsNewestFirst this review's own just-saved history row plus prior attempts
     *                                 for the same card, most-recent-first (mirrors
     *                                 {@link MasteryService}'s convention) and excluding boss-battle
     *                                 attempts; used only for leech detection/recovery evidence, so
     *                                 callers that don't need that can pass an empty list.
     */
    public Flashcard applyReviewOutcome(Flashcard card, ReviewRating rating, CardMasteryState masteryState,
                                        List<ReviewHistory> recentReviewsNewestFirst) {
        CardState current = card.getCardState();
        if (current == CardState.SUSPENDED) {
            return card;
        }
        if (card.getIntroducedAt() == null) {
            card.setIntroducedAt(LocalDateTime.now());
        }
        if (current == CardState.LEECH) {
            card.setCardState(recoverFromLeech(rating, masteryState, recentReviewsNewestFirst));
            return card;
        }
        CardState next = nextState(current, rating, masteryState);
        if (isLeech(card, recentReviewsNewestFirst, DEFAULT_LEECH_THRESHOLDS)) {
            next = CardState.LEECH;
        }
        card.setCardState(next);
        return card;
    }

    /**
     * A card leaves LEECH only on hard review evidence — a run of {@link LeechThresholds#recoveryStreak}
     * consecutive successful attempts (see {@link ReviewHistory#isSuccessfulAttempt}) — never merely
     * because it was edited; editing alone routes through {@link #resetForRewrite} instead. A single
     * Again keeps it flagged regardless of an otherwise-building streak, so one slip can't be masked
     * by counting attempts from before the slip.
     */
    private CardState recoverFromLeech(ReviewRating rating, CardMasteryState masteryState,
                                       List<ReviewHistory> recentReviewsNewestFirst) {
        if (rating == ReviewRating.AGAIN) {
            return CardState.LEECH;
        }
        int streak = DEFAULT_LEECH_THRESHOLDS.recoveryStreak();
        boolean recovered = recentReviewsNewestFirst.size() >= streak
                && recentReviewsNewestFirst.subList(0, streak).stream().allMatch(ReviewHistory::isSuccessfulAttempt);
        return recovered ? nextState(CardState.REVIEW, rating, masteryState) : CardState.LEECH;
    }

    /**
     * Evaluates the same objective evidence a learner would use to spot a poorly-written card:
     * repeated Again ratings, a high failure rate sustained across several attempts, repeated hint
     * use, or answers that consistently take far longer than the card's own time target. Any single
     * signal is sufficient — a card doesn't need to trip every threshold to need a rewrite. Pure and
     * package-private so the thresholds are directly unit testable without a database.
     */
    static boolean isLeech(Flashcard card, List<ReviewHistory> recentReviewsNewestFirst, LeechThresholds thresholds) {
        if (recentReviewsNewestFirst == null || recentReviewsNewestFirst.isEmpty()) {
            return false;
        }
        List<ReviewHistory> window = recentReviewsNewestFirst.size() > thresholds.lookbackReviews()
                ? recentReviewsNewestFirst.subList(0, thresholds.lookbackReviews())
                : recentReviewsNewestFirst;

        long againCount = window.stream().filter(history -> history.getRating() == ReviewRating.AGAIN).count();
        if (againCount >= thresholds.againCountThreshold()) {
            return true;
        }

        if (window.size() >= thresholds.minAttemptsForFailureRate()) {
            long failures = window.stream().filter(history -> !history.isSuccessfulAttempt()).count();
            if (failures / (double) window.size() >= thresholds.failureRateThreshold()) {
                return true;
            }
        }

        long hintUses = window.stream().filter(ReviewHistory::isHintUsed).count();
        if (hintUses >= thresholds.hintUseThreshold()) {
            return true;
        }

        if (card.getTimeLimitSeconds() != null && card.getTimeLimitSeconds() > 0) {
            long targetMs = card.getTimeLimitSeconds() * 1000L;
            long slowAttempts = window.stream()
                    .filter(history -> history.getResponseTimeMs() != null
                            && history.getResponseTimeMs() > targetMs * thresholds.responseTimeMultiplier())
                    .count();
            if (slowAttempts >= thresholds.excessiveResponseTimeCountThreshold()) {
                return true;
            }
        }

        return false;
    }

    private CardState nextState(CardState current, ReviewRating rating, CardMasteryState masteryState) {
        if (rating == ReviewRating.AGAIN) {
            return current == CardState.NEW || current == CardState.LEARNING
                    ? CardState.LEARNING
                    : CardState.RELEARNING;
        }
        if (masteryState == CardMasteryState.MASTERED) {
            return CardState.MASTERED;
        }
        return CardState.REVIEW;
    }

    /**
     * Diagnostically graduates a card the learner already knows straight out of the normal
     * learning phase: instead of the short intervals a single Easy rating would produce, it jumps
     * to a long, configurable interval (clamped to {@link #MIN_GRADUATION_INTERVAL_DAYS}-
     * {@link #MAX_GRADUATION_INTERVAL_DAYS} days) and is marked {@link CardState#GRADUATED} so it
     * resurfaces later for a retention check rather than being trusted forever. Callers must gate
     * this on {@link RatingGuardrail#canGraduate} themselves — this method does not re-validate
     * correctness/hint/timing, only applies the resulting state change. A no-op on a suspended
     * card, matching {@link #applyReviewOutcome}.
     */
    public Flashcard graduate(Flashcard card, int intervalDays) {
        if (card.getCardState() == CardState.SUSPENDED) {
            return card;
        }
        if (card.getIntroducedAt() == null) {
            card.setIntroducedAt(LocalDateTime.now());
        }
        int clampedInterval = Math.max(MIN_GRADUATION_INTERVAL_DAYS, Math.min(MAX_GRADUATION_INTERVAL_DAYS, intervalDays));
        card.setCardState(CardState.GRADUATED);
        card.setIntervalDays(clampedInterval);
        card.setDueDate(LocalDate.now().plusDays(clampedInterval));
        card.setReviewCount(card.getReviewCount() + 1);
        return card;
    }

    /**
     * Removes a card from every review queue until it is explicitly reactivated. Unlike
     * {@link #applyReviewOutcome}, this is intentionally allowed from any state (including
     * re-suspending an already-suspended card, which is a harmless no-op) since a learner may want
     * to suspend a card they've already progressed past.
     */
    public Flashcard suspend(Flashcard card) {
        card.setCardState(CardState.SUSPENDED);
        return card;
    }

    /**
     * Lets the learner restart a leech (or otherwise stuck) card's spaced-repetition progress after
     * rewriting it, instead of carrying over a depressed ease factor or an interval earned under the
     * old wording. Resets to NEW rather than LEARNING because only NEW cards are resurfaced by
     * {@code FlashcardRepository#findNewCards}; introducedAt is left untouched since it records when
     * the card was first introduced, not when its content last changed. Allowed from any state, like
     * {@link #suspend}, since a learner may want to reset a card before it's actually been flagged.
     */
    public Flashcard resetForRewrite(Flashcard card) {
        card.setCardState(CardState.NEW);
        card.setIntervalDays(0);
        card.setEaseFactor(DEFAULT_EASE_FACTOR);
        card.setDueDate(LocalDate.now());
        return card;
    }

    /**
     * @param lookbackReviews                    most-recent reviews considered as evidence, newest first
     * @param againCountThreshold                 Again ratings within the lookback that alone indicate a leech
     * @param minAttemptsForFailureRate           attempts required before the failure-rate signal applies
     * @param failureRateThreshold                share (0-1) of unsuccessful attempts within the lookback that indicates a leech
     * @param hintUseThreshold                    hint uses within the lookback that alone indicate a leech
     * @param responseTimeMultiplier              how many times the card's own time limit a response must exceed to count as excessive
     * @param excessiveResponseTimeCountThreshold excessively slow attempts within the lookback that indicate a leech
     * @param recoveryStreak                      consecutive successful attempts required to leave LEECH
     */
    public record LeechThresholds(int lookbackReviews, int againCountThreshold, int minAttemptsForFailureRate,
                                  double failureRateThreshold, int hintUseThreshold, double responseTimeMultiplier,
                                  int excessiveResponseTimeCountThreshold, int recoveryStreak) {
    }
}
