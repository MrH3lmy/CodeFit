package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryFilter;
import com.codefit.repository.ReviewHistoryRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class StatsService {
    private static final int RECENT_SKILL_REVIEW_LIMIT = 100;
    private static final int READINESS_REVIEW_LIMIT = 50;
    private static final int CONSISTENCY_WINDOW_DAYS = 7;

    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ProgressService progressService = new ProgressService();
    private final MasteryService masteryService = new MasteryService();

    public UserProgress getProgress() {
        return progressService.getProgress();
    }

    public int getTotalCards() {
        return flashcardRepository.countAll();
    }

    public int getDueCards() {
        return flashcardRepository.countDue();
    }

    public int getReviewedToday() {
        return reviewHistoryRepository.countReviewedToday();
    }

    public List<ReviewHistory> getRecentReviews() {
        return reviewHistoryRepository.findRecent(10);
    }

    public WeeklyBossResult getLatestWeeklyBossResult() {
        List<ReviewHistory> bossReviews = reviewHistoryRepository.findRecentBossBattles(50);
        if (bossReviews.isEmpty()) {
            return WeeklyBossResult.empty();
        }
        LocalDate latestDate = bossReviews.getFirst().getReviewedAt().toLocalDate();
        List<ReviewHistory> latestSession = bossReviews.stream()
                .filter(history -> history.getReviewedAt().toLocalDate().equals(latestDate))
                .toList();
        double score = getOverallRecentAccuracy(latestSession);
        Map<Long, Flashcard> cardsById = flashcardRepository.findAll().stream()
                .collect(Collectors.toMap(Flashcard::getId, card -> card));
        Map<String, SkillAccumulator> bySkill = new HashMap<>();
        latestSession.forEach(history -> {
            Flashcard card = cardsById.get(history.getFlashcardId());
            String skill = card == null ? "Deleted cards" : normalizeSkill(card.getSkillCategory());
            bySkill.computeIfAbsent(skill, SkillAccumulator::new).record(history);
        });
        List<String> weakAreas = bySkill.values().stream()
                .map(SkillAccumulator::toPerformance)
                .filter(performance -> performance.needsPracticeRate() > 0)
                .sorted(Comparator.comparingDouble(StatsSkillPerformance::needsPracticeRate).reversed())
                .limit(3)
                .map(StatsSkillPerformance::skillCategory)
                .toList();
        String focus = weakAreas.isEmpty()
                ? "Maintain strength with mixed timed practice and one stretch card."
                : "Prioritize " + String.join(", ", weakAreas) + " with due-card drills and new targeted prompts.";
        return new WeeklyBossResult(true, latestSession.size(), score, weakAreas, focus);
    }

    public boolean isWeeklyBossAvailable() {
        return new ReviewService().isWeeklyBossAvailable();
    }

    public EngineerReadinessStats getEngineerReadinessStats() {
        List<ReviewHistory> recentReviews = reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT);
        double recentAccuracy = getOverallRecentAccuracy(recentReviews);
        double timedSuccessRate = getTimedSuccessRate(recentReviews);
        double weakAreaRate = getWeakAreaRate(recentReviews);
        double consistencyScore = getConsistencyScore(recentReviews);
        double subjectiveSelfAssessmentRate = getSubjectiveSelfAssessmentRate(recentReviews);
        double confidenceCalibrationScore = getConfidenceCalibrationScore(recentReviews);
        int confidenceSampleCount = getConfidenceSampleCount(recentReviews);
        double readinessScore = recentReviews.isEmpty() ? 0.0
                : (recentAccuracy * 0.40) + (timedSuccessRate * 0.25)
                + ((100.0 - weakAreaRate) * 0.20) + (consistencyScore * 0.15);

        return new EngineerReadinessStats(recentReviews.size(), readinessScore, recentAccuracy,
                timedSuccessRate, weakAreaRate, consistencyScore, subjectiveSelfAssessmentRate,
                confidenceCalibrationScore, confidenceSampleCount);
    }

    public double getOverallRecentAccuracy() {
        return getOverallRecentAccuracy(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getTimedSuccessRate() {
        return getTimedSuccessRate(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getWeakAreaRate() {
        return getWeakAreaRate(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    public double getConsistencyScore() {
        return getConsistencyScore(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    /**
     * Whether training time is producing durable knowledge efficiently, as opposed to XP/streak/
     * review-count signals which mainly reflect activity. Unlike the readiness stats above (which
     * intentionally look only at the most recent {@value #READINESS_REVIEW_LIMIT} reviews), this
     * reads the full history in scope so long-horizon figures like the 30+ day retention bucket
     * aren't starved by a small recency window.
     */
    public LearningEfficiencyStats getLearningEfficiencyStats(ReviewHistoryFilter filter) {
        List<ReviewHistory> reviews = reviewHistoryRepository.findFiltered(filter);
        List<Flashcard> allCards = flashcardRepository.findAll();
        Map<Long, Flashcard> cardsById = allCards.stream().collect(Collectors.toMap(Flashcard::getId, card -> card));
        int masteredInScope = masteryService.summarize(filterCardsByScope(allCards, filter)).masteredCards();
        return buildLearningEfficiencyStats(reviews, cardsById, masteredInScope);
    }

    public LearningEfficiencyStats getLearningEfficiencyStats() {
        return getLearningEfficiencyStats(ReviewHistoryFilter.all());
    }

    /** Card-level filters (deck/skill/card type) applied to the population mastery is measured
     *  against; the date range only bounds the review-level metrics, since mastery is a snapshot
     *  of current state rather than something a training-time window can meaningfully restrict. */
    private List<Flashcard> filterCardsByScope(List<Flashcard> allCards, ReviewHistoryFilter filter) {
        return allCards.stream()
                .filter(card -> filter.deckId() == null || card.getDeckId() == filter.deckId())
                .filter(card -> filter.skillCategory() == null || filter.skillCategory().isBlank()
                        || normalizeSkill(card.getSkillCategory()).equalsIgnoreCase(filter.skillCategory()))
                .filter(card -> filter.cardType() == null || card.getCardType() == filter.cardType())
                .toList();
    }

    /**
     * Pure aggregation over an already-filtered review list, independent of the database, so every
     * efficiency figure is directly unit testable. {@code cardsById} only needs to cover the cards
     * referenced by {@code reviews}; {@code masteredInScope} must come from
     * {@link MasteryService#summarize} so mastery-per-hour agrees with the mastery breakdown shown
     * elsewhere instead of inventing a parallel definition.
     */
    static LearningEfficiencyStats buildLearningEfficiencyStats(List<ReviewHistory> reviews,
                                                                 Map<Long, Flashcard> cardsById, int masteredInScope) {
        double activeMinutes = totalActiveMinutes(reviews);
        double activeHours = activeMinutes / 60.0;
        boolean hasTimeSignal = activeHours >= LearningEfficiencyStats.MIN_ACTIVE_HOURS_FOR_RATE_SIGNAL;

        double masteredPerHour = hasTimeSignal ? masteredInScope / activeHours : 0.0;

        long objectiveRecallCount = reviews.stream().filter(history -> !history.isSubjective() && history.isObjectivelyCorrect()).count();
        double recallsPerMinute = hasTimeSignal ? objectiveRecallCount / activeMinutes : 0.0;

        RecoveredMissResult recovered = computeRecoveredMisses(reviews);
        double recoveredPerSession = recovered.sessionCount() == 0 ? 0.0
                : recovered.recoveredCount() * 1.0 / recovered.sessionCount();

        LearningEfficiencyStats.RetentionByInterval retention = computeRetentionByInterval(reviews);
        Map<String, Double> minutesBySkill = activeMinutesBySkill(reviews, cardsById);
        Map<CardType, Double> minutesByCardType = activeMinutesByCardType(reviews, cardsById);
        SuspendedCardTime suspended = computeSuspendedCardTime(reviews, cardsById);

        double confidenceCalibration = getConfidenceCalibrationScore(reviews);
        int confidenceSamples = getConfidenceSampleCount(reviews);

        return new LearningEfficiencyStats(reviews.size(), activeHours, masteredInScope, masteredPerHour,
                (int) objectiveRecallCount, recallsPerMinute, recovered.sessionCount(), recovered.recoveredCount(),
                recoveredPerSession, retention, minutesBySkill, minutesByCardType,
                suspended.cardCount(), suspended.activeMinutes(), confidenceCalibration, confidenceSamples);
    }

    /**
     * Active review time sums each attempt's own recorded response time rather than wall-clock
     * time between app open and close, so idle time (app left open, breaks mid-session) is never
     * counted as training time. This mirrors ReviewController's per-session time budget, which is
     * likewise accumulated from response times rather than a wall-clock session timer.
     */
    static double totalActiveMinutes(List<ReviewHistory> reviews) {
        long totalMs = reviews.stream()
                .map(ReviewHistory::getResponseTimeMs)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
        return totalMs / 60_000.0;
    }

    record RecoveredMissResult(int sessionCount, int recoveredCount) {
    }

    /**
     * A "miss" is an unsuccessful attempt (objectively incorrect, or self-rated Again/Hard for
     * subjective cards); it is "recovered" if the same card is attempted again later in the same
     * training session (grouped by sessionId) and that later attempt succeeds. Counted per
     * distinct card, not per attempt, so retrying one flaky card repeatedly can't inflate the
     * score, then summed across all sessions and divided by the number of sessions with any review
     * in scope. Reviews without a sessionId (legacy rows) are excluded from both sides of that
     * ratio since they can't be grouped into a session at all.
     */
    static RecoveredMissResult computeRecoveredMisses(List<ReviewHistory> reviews) {
        Map<String, List<ReviewHistory>> bySession = reviews.stream()
                .filter(history -> history.getSessionId() != null && !history.getSessionId().isBlank())
                .collect(Collectors.groupingBy(ReviewHistory::getSessionId));

        int recovered = 0;
        for (List<ReviewHistory> sessionReviews : bySession.values()) {
            List<ReviewHistory> ordered = sessionReviews.stream()
                    .sorted(Comparator.comparing(ReviewHistory::getReviewedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparingLong(ReviewHistory::getId))
                    .toList();
            Set<Long> missedCards = new HashSet<>();
            Set<Long> recoveredCards = new HashSet<>();
            for (ReviewHistory history : ordered) {
                if (isSuccessfulAttempt(history)) {
                    if (missedCards.remove(history.getFlashcardId())) {
                        recoveredCards.add(history.getFlashcardId());
                    }
                } else {
                    missedCards.add(history.getFlashcardId());
                }
            }
            recovered += recoveredCards.size();
        }
        return new RecoveredMissResult(bySession.size(), recovered);
    }

    /** Shared "did this attempt succeed" definition for recovered-misses and retention, since both
     *  need a uniform pass/fail signal across objective and subjective (self-rated) cards. */
    private static boolean isSuccessfulAttempt(ReviewHistory history) {
        return history.isSubjective()
                ? (history.getRating() == ReviewRating.GOOD || history.getRating() == ReviewRating.EASY)
                : history.isObjectivelyCorrect();
    }

    /**
     * Buckets each review by the gap since its previous attempt (previousIntervalDays), matching
     * the issue's 7/14/30+ day retention checkpoints. Reviews with a shorter gap say nothing about
     * longer-horizon retention and are excluded from every bucket rather than padding a "day 0"
     * one that nobody asked for.
     */
    static LearningEfficiencyStats.RetentionByInterval computeRetentionByInterval(List<ReviewHistory> reviews) {
        int[] sample = new int[3];
        int[] retained = new int[3];
        for (ReviewHistory history : reviews) {
            int bucket = retentionBucketIndex(history.getPreviousIntervalDays());
            if (bucket < 0) {
                continue;
            }
            sample[bucket]++;
            if (isSuccessfulAttempt(history)) {
                retained[bucket]++;
            }
        }
        return new LearningEfficiencyStats.RetentionByInterval(
                new LearningEfficiencyStats.RetentionBucket(sample[0], retained[0]),
                new LearningEfficiencyStats.RetentionBucket(sample[1], retained[1]),
                new LearningEfficiencyStats.RetentionBucket(sample[2], retained[2]));
    }

    private static int retentionBucketIndex(int previousIntervalDays) {
        if (previousIntervalDays >= 30) {
            return 2;
        }
        if (previousIntervalDays >= 14) {
            return 1;
        }
        if (previousIntervalDays >= 7) {
            return 0;
        }
        return -1;
    }

    /** Active minutes spent per skill category, keyed the same way skill performance already is. */
    static Map<String, Double> activeMinutesBySkill(List<ReviewHistory> reviews, Map<Long, Flashcard> cardsById) {
        Map<String, Double> minutesBySkill = new HashMap<>();
        for (ReviewHistory history : reviews) {
            Integer responseTimeMs = history.getResponseTimeMs();
            if (responseTimeMs == null) {
                continue;
            }
            Flashcard card = cardsById.get(history.getFlashcardId());
            String skill = card == null ? "Deleted cards" : normalizeSkill(card.getSkillCategory());
            minutesBySkill.merge(skill, responseTimeMs / 60_000.0, Double::sum);
        }
        return minutesBySkill;
    }

    /** Active minutes spent per card type; deleted cards have no recoverable type and are omitted
     *  rather than folded into a misleading bucket. */
    static Map<CardType, Double> activeMinutesByCardType(List<ReviewHistory> reviews, Map<Long, Flashcard> cardsById) {
        Map<CardType, Double> minutesByType = new HashMap<>();
        for (ReviewHistory history : reviews) {
            Integer responseTimeMs = history.getResponseTimeMs();
            Flashcard card = cardsById.get(history.getFlashcardId());
            if (responseTimeMs == null || card == null) {
                continue;
            }
            minutesByType.merge(card.getCardType(), responseTimeMs / 60_000.0, Double::sum);
        }
        return minutesByType;
    }

    record SuspendedCardTime(int cardCount, double activeMinutes) {
    }

    /**
     * Time spent on cards that are currently suspended. "Suspended" is the only lifecycle state
     * available for this at write time (see {@link CardState}); a leech-specific state does not
     * exist yet, so leech time-tracking is intentionally out of scope here rather than invented
     * ad hoc.
     */
    static SuspendedCardTime computeSuspendedCardTime(List<ReviewHistory> reviews, Map<Long, Flashcard> cardsById) {
        Set<Long> suspendedCards = new HashSet<>();
        double minutes = 0.0;
        for (ReviewHistory history : reviews) {
            Flashcard card = cardsById.get(history.getFlashcardId());
            if (card == null || card.getCardState() != CardState.SUSPENDED) {
                continue;
            }
            Integer responseTimeMs = history.getResponseTimeMs();
            if (responseTimeMs != null) {
                minutes += responseTimeMs / 60_000.0;
            }
            suspendedCards.add(history.getFlashcardId());
        }
        return new SuspendedCardTime(suspendedCards.size(), minutes);
    }

    /**
     * Objective accuracy is computed only over objectively-graded (non-subjective) attempts.
     * Subjective (CONCEPT) attempts have no text-match signal and must be excluded from both the
     * numerator and the denominator rather than counted as incorrect. Package-private (not
     * private) and static so this pure aggregation logic is directly unit testable.
     */
    static double getOverallRecentAccuracy(List<ReviewHistory> recentReviews) {
        List<ReviewHistory> objectiveReviews = recentReviews.stream().filter(history -> !history.isSubjective()).toList();
        if (objectiveReviews.isEmpty()) {
            return 0.0;
        }
        long correctReviews = objectiveReviews.stream()
                .filter(ReviewHistory::isObjectivelyCorrect)
                .count();
        return correctReviews * 100.0 / objectiveReviews.size();
    }

    static double getTimedSuccessRate(List<ReviewHistory> recentReviews) {
        List<ReviewHistory> objectiveReviews = recentReviews.stream().filter(history -> !history.isSubjective()).toList();
        if (objectiveReviews.isEmpty()) {
            return 0.0;
        }
        long timedSuccesses = objectiveReviews.stream()
                .filter(ReviewHistory::isTimedSuccess)
                .count();
        return timedSuccesses * 100.0 / objectiveReviews.size();
    }

    /** % of subjective (CONCEPT) self-graded attempts the learner rated Good or Easy. */
    public double getSubjectiveSelfAssessmentRate() {
        return getSubjectiveSelfAssessmentRate(reviewHistoryRepository.findRecent(READINESS_REVIEW_LIMIT));
    }

    static double getSubjectiveSelfAssessmentRate(List<ReviewHistory> recentReviews) {
        List<ReviewHistory> subjectiveReviews = recentReviews.stream().filter(ReviewHistory::isSubjective).toList();
        if (subjectiveReviews.isEmpty()) {
            return 0.0;
        }
        long selfRatedGood = subjectiveReviews.stream()
                .filter(history -> history.getRating() == ReviewRating.GOOD || history.getRating() == ReviewRating.EASY)
                .count();
        return selfRatedGood * 100.0 / subjectiveReviews.size();
    }

    /**
     * % of confidently-rated (HIGH or LOW), objectively-graded attempts where the learner's stated
     * confidence matched the actual outcome (HIGH + correct, or LOW + incorrect). The scheduler
     * rating never feeds this — confidence is recorded independently precisely so it can be
     * checked against reality instead of assumed from the rating. MEDIUM confidence and subjective
     * (CONCEPT) attempts are excluded since there is no clear "should have known better" signal.
     */
    static double getConfidenceCalibrationScore(List<ReviewHistory> recentReviews) {
        List<ReviewHistory> calibratable = confidenceCalibratableReviews(recentReviews);
        if (calibratable.isEmpty()) {
            return 0.0;
        }
        long calibrated = calibratable.stream().filter(StatsService::isConfidenceCalibrated).count();
        return calibrated * 100.0 / calibratable.size();
    }

    static int getConfidenceSampleCount(List<ReviewHistory> recentReviews) {
        return confidenceCalibratableReviews(recentReviews).size();
    }

    private static List<ReviewHistory> confidenceCalibratableReviews(List<ReviewHistory> recentReviews) {
        return recentReviews.stream()
                .filter(history -> !history.isSubjective())
                .filter(history -> "HIGH".equals(history.getConfidence()) || "LOW".equals(history.getConfidence()))
                .toList();
    }

    private static boolean isConfidenceCalibrated(ReviewHistory history) {
        boolean correct = history.isObjectivelyCorrect();
        return ("HIGH".equals(history.getConfidence()) && correct)
                || ("LOW".equals(history.getConfidence()) && !correct);
    }

    private double getWeakAreaRate(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }
        long weakAreaReviews = recentReviews.stream()
                .filter(history -> history.getRating() == ReviewRating.AGAIN || history.getRating() == ReviewRating.HARD)
                .count();
        return weakAreaReviews * 100.0 / recentReviews.size();
    }

    private double getConsistencyScore(List<ReviewHistory> recentReviews) {
        if (recentReviews.isEmpty()) {
            return 0.0;
        }

        LocalDate today = LocalDate.now();
        Set<LocalDate> activeReviewDates = recentReviews.stream()
                .map(ReviewHistory::getReviewedAt)
                .map(reviewedAt -> reviewedAt == null ? today : reviewedAt.toLocalDate())
                .filter(reviewDate -> !reviewDate.isAfter(today))
                .filter(reviewDate -> ChronoUnit.DAYS.between(reviewDate, today) < CONSISTENCY_WINDOW_DAYS)
                .collect(Collectors.toSet());

        return activeReviewDates.size() * 100.0 / CONSISTENCY_WINDOW_DAYS;
    }

    public List<StatsSkillPerformance> getSkillPerformance() {
        Map<Long, Flashcard> cardsById = flashcardRepository.findAll().stream()
                .collect(Collectors.toMap(Flashcard::getId, card -> card));
        Map<String, SkillAccumulator> bySkill = new HashMap<>();
        LocalDate today = LocalDate.now();

        cardsById.values().forEach(card -> {
            SkillAccumulator accumulator = bySkill.computeIfAbsent(normalizeSkill(card.getSkillCategory()), SkillAccumulator::new);
            accumulator.totalCards++;
            if (card.getDueDate() != null && !card.getDueDate().isAfter(today)) {
                accumulator.dueCards++;
            }
        });

        reviewHistoryRepository.findRecent(RECENT_SKILL_REVIEW_LIMIT).forEach(history -> {
            Flashcard card = cardsById.get(history.getFlashcardId());
            String skill = card == null ? "Deleted cards" : normalizeSkill(card.getSkillCategory());
            bySkill.computeIfAbsent(skill, SkillAccumulator::new).record(history);
        });

        return bySkill.values().stream()
                .map(SkillAccumulator::toPerformance)
                .sorted(Comparator.comparing(StatsSkillPerformance::needsPractice).reversed()
                        .thenComparing(StatsSkillPerformance::accuracyPercent)
                        .thenComparing(StatsSkillPerformance::skillCategory))
                .toList();
    }

    public List<StatsSkillPerformance> getNeedsPracticeSkills() {
        return getSkillPerformance().stream()
                .filter(StatsSkillPerformance::needsPractice)
                .toList();
    }

    /**
     * Diagnostically graduated ("already know this") cards are tracked separately from cards that
     * earned MASTERED status through the normal repeated-review bar (see MasteryService), so a
     * learner can see how much of their progress is a single-shot diagnostic skip still pending a
     * retention check versus durably proven mastery.
     */
    public CardStateBreakdown getCardStateBreakdown() {
        return summarizeCardStates(flashcardRepository.findAll());
    }

    static CardStateBreakdown summarizeCardStates(List<Flashcard> cards) {
        int graduated = (int) cards.stream().filter(card -> card.getCardState() == CardState.GRADUATED).count();
        int suspended = (int) cards.stream().filter(card -> card.getCardState() == CardState.SUSPENDED).count();
        return new CardStateBreakdown(graduated, suspended);
    }

    public record CardStateBreakdown(int graduatedCards, int suspendedCards) {
    }

    private static String normalizeSkill(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }

    private static final class SkillAccumulator {
        private final String skill;
        private int totalCards;
        private int dueCards;
        private int objectiveReviewCount;
        private int correctCount;
        private int againCount;
        private int hardCount;
        private int goodCount;
        private int easyCount;

        private SkillAccumulator(String skill) {
            this.skill = skill;
        }

        private void record(ReviewHistory history) {
            ReviewRating rating = history == null ? null : history.getRating();
            if (rating == null) {
                return;
            }
            switch (rating) {
                case AGAIN -> againCount++;
                case HARD -> hardCount++;
                case GOOD -> goodCount++;
                case EASY -> easyCount++;
            }
            if (!history.isSubjective()) {
                objectiveReviewCount++;
                if (history.isObjectivelyCorrect()) {
                    correctCount++;
                }
            }
        }

        private StatsSkillPerformance toPerformance() {
            return new StatsSkillPerformance(skill, totalCards, dueCards,
                    againCount + hardCount + goodCount + easyCount, objectiveReviewCount, correctCount,
                    againCount, hardCount, goodCount, easyCount);
        }
    }
}
