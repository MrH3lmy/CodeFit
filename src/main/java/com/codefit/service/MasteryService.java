package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.ReviewHistoryRepository;

import java.util.List;

/**
 * Centralizes the definition of "mastered" so deck, syllabus, dashboard, and training-path
 * progress all agree on the same durable-mastery calculation instead of each treating a single
 * attempt (reviewCount > 0) as progress.
 */
public class MasteryService {
    public static final MasteryThresholds DEFAULT_THRESHOLDS = new MasteryThresholds(2, 14, 3);
    private static final int REVIEW_HISTORY_LOOKBACK = 10;

    private final ReviewHistoryRepository reviewHistoryRepository;
    private final MasteryThresholds thresholds;

    public MasteryService() {
        this(new ReviewHistoryRepository(), DEFAULT_THRESHOLDS);
    }

    public MasteryService(ReviewHistoryRepository reviewHistoryRepository, MasteryThresholds thresholds) {
        this.reviewHistoryRepository = reviewHistoryRepository;
        this.thresholds = thresholds;
    }

    public CardMasteryState getMasteryState(Flashcard card) {
        List<ReviewHistory> recentReviews = reviewHistoryRepository.findRecentForFlashcard(card.getId(), REVIEW_HISTORY_LOOKBACK);
        return evaluate(card, recentReviews, thresholds);
    }

    public MasteryBreakdown summarize(List<Flashcard> cards) {
        int mastered = 0;
        int learning = 0;
        for (Flashcard card : cards) {
            CardMasteryState state = getMasteryState(card);
            if (state == CardMasteryState.MASTERED) {
                mastered++;
            } else if (state == CardMasteryState.LEARNING) {
                learning++;
            }
        }
        return new MasteryBreakdown(cards.size(), learning + mastered, learning, mastered);
    }

    /**
     * Pure decision logic, independent of the database, so scheduling/mastery rules can be unit
     * tested directly. {@code recentReviewsNewestFirst} must be ordered most-recent-first and
     * exclude boss-battle attempts.
     */
    public static CardMasteryState evaluate(Flashcard card, List<ReviewHistory> recentReviewsNewestFirst,
                                            MasteryThresholds thresholds) {
        if (card.getReviewCount() <= 0 || recentReviewsNewestFirst == null || recentReviewsNewestFirst.isEmpty()) {
            return CardMasteryState.NOT_SEEN;
        }
        boolean mastered = card.getCardType() == CardType.CONCEPT
                ? isSubjectivelyMastered(card, recentReviewsNewestFirst, thresholds)
                : isMastered(card, recentReviewsNewestFirst, thresholds);
        return mastered ? CardMasteryState.MASTERED : CardMasteryState.LEARNING;
    }

    private static boolean isMastered(Flashcard card, List<ReviewHistory> recentReviewsNewestFirst,
                                      MasteryThresholds thresholds) {
        if (card.getIntervalDays() < thresholds.minIntervalDays()) {
            return false;
        }
        if (recentReviewsNewestFirst.size() < thresholds.minConsecutiveCorrect()) {
            return false;
        }
        boolean lastReviewsCorrect = recentReviewsNewestFirst.subList(0, thresholds.minConsecutiveCorrect()).stream()
                .allMatch(ReviewHistory::isObjectivelyCorrect);
        if (!lastReviewsCorrect) {
            return false;
        }
        int windowSize = Math.min(thresholds.noAgainWindow(), recentReviewsNewestFirst.size());
        boolean noRecentAgain = recentReviewsNewestFirst.subList(0, windowSize).stream()
                .noneMatch(history -> history.getRating() == ReviewRating.AGAIN);
        if (!noRecentAgain) {
            return false;
        }
        if (card.getTimeLimitSeconds() != null) {
            Integer lastResponseTimeMs = recentReviewsNewestFirst.get(0).getResponseTimeMs();
            if (lastResponseTimeMs != null && lastResponseTimeMs > card.getTimeLimitSeconds() * 1000L) {
                return false;
            }
        }
        return true;
    }

    /**
     * Subjective (CONCEPT) cards are never text-matched, so mastery can't rely on objective
     * correctness. Instead require a run of consecutive self-rated Good/Easy passes (no Again or
     * Hard in that window) alongside the same mature-interval bar used for objective cards.
     */
    private static boolean isSubjectivelyMastered(Flashcard card, List<ReviewHistory> recentReviewsNewestFirst,
                                                   MasteryThresholds thresholds) {
        if (card.getIntervalDays() < thresholds.minIntervalDays()) {
            return false;
        }
        if (recentReviewsNewestFirst.size() < thresholds.minConsecutiveCorrect()) {
            return false;
        }
        return recentReviewsNewestFirst.subList(0, thresholds.minConsecutiveCorrect()).stream()
                .allMatch(history -> history.getRating() == ReviewRating.GOOD || history.getRating() == ReviewRating.EASY);
    }

    public enum CardMasteryState {
        NOT_SEEN, LEARNING, MASTERED
    }

    /**
     * @param minConsecutiveCorrect number of most-recent reviews that must all be objectively correct
     * @param minIntervalDays       minimum current scheduler interval, in days
     * @param noAgainWindow         number of most-recent reviews that must contain no AGAIN rating
     */
    public record MasteryThresholds(int minConsecutiveCorrect, int minIntervalDays, int noAgainWindow) {
    }

    public record MasteryBreakdown(int totalCards, int seenCards, int learningCards, int masteredCards) {
        public double seenPercent() {
            return totalCards == 0 ? 0.0 : seenCards * 100.0 / totalCards;
        }

        public double learningPercent() {
            return totalCards == 0 ? 0.0 : learningCards * 100.0 / totalCards;
        }

        public double masteredPercent() {
            return totalCards == 0 ? 0.0 : masteredCards * 100.0 / totalCards;
        }
    }
}
