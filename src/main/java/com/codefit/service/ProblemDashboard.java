package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvingPhase;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The full problem-solving progress dashboard (#147): every figure here is computed from
 * {@link com.codefit.model.ProblemProgress}, {@link com.codefit.model.ProblemAttempt}, and
 * {@link com.codefit.model.RoadmapEntry} rows at read time by {@link ProblemDashboardService} — none
 * of it is a duplicated counter persisted anywhere, so it is always exactly as fresh as the last
 * saved attempt or progress update.
 */
public record ProblemDashboard(
        CoreProgress coreProgress,
        QualityMetrics qualityMetrics,
        TimingInsights timingInsights,
        List<TopicInsight> topicInsights,
        Recommendation recommendation,
        List<ReflectionGap> overdueReflections,
        List<UnfinishedAttempt> unfinishedAttempts
) {

    /** Minimum sample size before a rate-based metric is shown instead of an "not enough data yet"
     *  label — a single problem should never look like a trend. Shared across quality and topic
     *  metrics so "enough sample" means the same thing everywhere on this dashboard. */
    public static final int MIN_SAMPLE_SIZE = 3;

    public record CoreProgress(
            RoadmapStage currentStage,
            Integer currentSet,
            boolean roadmapComplete,
            int mandatoryTotal,
            int mandatoryCompleted,
            int optionalTotal,
            int optionalCompleted,
            List<StageProgress> stageBreakdown,
            StatusBreakdown statusBreakdown,
            List<WeeklyCount> problemsSolvedPerWeek
    ) {
        public int mandatoryRemaining() {
            return mandatoryTotal - mandatoryCompleted;
        }
    }

    public record StageProgress(RoadmapStage stage, int total, int solved) {
        public double completionPercent() {
            return total == 0 ? 0.0 : solved * 100.0 / total;
        }
    }

    /**
     * Buckets every roadmap problem into exactly one of five mutually exclusive outcomes. {@code AC}
     * and {@code ACX} split {@link com.codefit.model.ProblemState#SOLVED} by the verdict of the
     * problem's most recent attempt (defaulting to {@code AC} when a problem was imported directly
     * as solved with no attempt row at all — see {@code TrainingSheetImportService}); {@code CS}
     * ("could not solve") is {@link com.codefit.model.ProblemState#NEEDS_REVISIT}, named after the
     * Solving Workspace's "Could Not Solve" finish action that produces it.
     */
    public record StatusBreakdown(int acCount, int acxCount, int couldNotSolveCount, int inProgressCount, int notStartedCount) {
        public int total() {
            return acCount + acxCount + couldNotSolveCount + inProgressCount + notStartedCount;
        }
    }

    public record WeeklyCount(LocalDate weekStart, int solvedCount) {
    }

    /**
     * Every rate is paired with the sample count it was computed from, so the dashboard can show
     * "not enough data yet" below {@link #MIN_SAMPLE_SIZE} instead of a misleading percentage.
     */
    public record QualityMetrics(
            int firstSubmissionSampleCount,
            double firstSubmissionAccuracyPercent,
            int independenceSampleCount,
            double independentSolveRatePercent,
            double editorialDependencyRatePercent,
            int acceptedSampleCount,
            double averageSubmissionsPerAccepted,
            int perceivedDifficultySampleCount,
            double averagePerceivedDifficulty,
            int mandatoryTotal,
            int mandatoryCompleted,
            int optionalTotal,
            int optionalCompleted
    ) {
        public boolean hasFirstSubmissionSignal() {
            return firstSubmissionSampleCount >= MIN_SAMPLE_SIZE;
        }

        public boolean hasIndependenceSignal() {
            return independenceSampleCount >= MIN_SAMPLE_SIZE;
        }

        public boolean hasAcceptedSampleSignal() {
            return acceptedSampleCount >= MIN_SAMPLE_SIZE;
        }

        public boolean hasPerceivedDifficultySignal() {
            return perceivedDifficultySampleCount >= MIN_SAMPLE_SIZE;
        }

        public double mandatoryCompletionPercent() {
            return mandatoryTotal == 0 ? 0.0 : mandatoryCompleted * 100.0 / mandatoryTotal;
        }

        public double optionalCompletionPercent() {
            return optionalTotal == 0 ? 0.0 : optionalCompleted * 100.0 / optionalTotal;
        }
    }

    /**
     * {@code bottleneckPhase} is the phase with the greatest total accumulated time across every
     * attempt in scope — deterministic, ties broken by {@link SolvingPhase}'s declared order
     * (Reading, Thinking, Coding, Debugging) — {@code null} only when there is no timing signal at
     * all. Every second here already excludes paused time, since {@code ProblemSolvingSession} never
     * accumulates time while paused (#145).
     */
    public record TimingInsights(
            int sampleCount,
            double averageReadingSeconds,
            double averageThinkingSeconds,
            double averageCodingSeconds,
            double averageDebuggingSeconds,
            long totalSolvingSeconds,
            SolvingPhase bottleneckPhase
    ) {
        public boolean hasSignal() {
            return sampleCount > 0;
        }
    }

    public enum TopicCategory { STRONG, DEVELOPING, WEAK, INSUFFICIENT_SAMPLE }

    /**
     * {@code accuracyPercent} is the share of attempted problems in this topic that ended
     * {@code SOLVED}; {@code independencePercent} is the share of those solved problems solved with
     * {@code SolvedWith.SELF}. Categorized {@link TopicCategory#INSUFFICIENT_SAMPLE} below
     * {@link #MIN_SAMPLE_SIZE} attempted problems rather than guessing STRONG/WEAK from one or two
     * data points.
     */
    public record TopicInsight(String topic, int sampleCount, double accuracyPercent, double independencePercent,
                                TopicCategory category) {
    }

    /** {@code entry} is empty once every roadmap problem is solved; {@code reason} always explains
     *  in plain language why this specific problem (or the empty state) was chosen. */
    public record Recommendation(Optional<ProblemLibraryEntry> entry, String reason) {
    }

    public record ReflectionGap(Problem problem, ProblemProgress progress) {
    }

    public record UnfinishedAttempt(Problem problem, ProblemSolvingSession session) {
    }
}
