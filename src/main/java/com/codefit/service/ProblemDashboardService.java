package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.ProblemSolvingSessionRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns stored problem-solving data into the dashboards and coaching insights (#147): every figure
 * is computed at read time from {@link RoadmapEntry}, {@link ProblemProgress}, and
 * {@link ProblemAttempt} rows — nothing here is a separately persisted counter, so every metric is
 * automatically correct the moment a new attempt or progress update is saved.
 *
 * <p>Aggregation loads each table once (a handful of queries total, not one per problem) and does the
 * rest in memory, so it stays responsive regardless of roadmap size. The heavy lifting is done by
 * package-private static methods over plain lists/maps, mirroring {@link StatsService}, so every
 * metric is independently unit testable without touching the database.
 */
public class ProblemDashboardService {

    private final ProblemRepository problemRepository;
    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ProblemProgressRepository progressRepository;
    private final ProblemAttemptRepository attemptRepository;
    private final ProblemSolvingSessionRepository sessionRepository;
    private final ProblemLibraryService problemLibraryService;

    public ProblemDashboardService() {
        this(new ProblemRepository(), new RoadmapEntryRepository(), new ProblemProgressRepository(),
                new ProblemAttemptRepository(), new ProblemSolvingSessionRepository(), new ProblemLibraryService());
    }

    public ProblemDashboardService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                   ProblemProgressRepository progressRepository, ProblemAttemptRepository attemptRepository,
                                   ProblemSolvingSessionRepository sessionRepository, ProblemLibraryService problemLibraryService) {
        this.problemRepository = problemRepository;
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.progressRepository = progressRepository;
        this.attemptRepository = attemptRepository;
        this.sessionRepository = sessionRepository;
        this.problemLibraryService = problemLibraryService;
    }

    public ProblemDashboard build(ProblemDashboardFilter filter) {
        List<RoadmapEntry> roadmapEntries = roadmapEntryRepository.findAllInRoadmapOrder();
        List<Problem> problems = problemRepository.findAll();
        List<ProblemProgress> progressRows = progressRepository.findAll();
        List<ProblemAttempt> attempts = attemptRepository.findAll();

        Map<Long, Problem> problemsById = problems.stream().collect(Collectors.toMap(Problem::getId, p -> p));
        Map<Long, ProblemProgress> progressByProblemId = progressRows.stream()
                .collect(Collectors.toMap(ProblemProgress::getProblemId, p -> p));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = attempts.stream()
                .collect(Collectors.groupingBy(ProblemAttempt::problemId));

        Set<Long> scopedProblemIds = filter.stage() == null ? null
                : roadmapEntries.stream().filter(entry -> entry.getStage() == filter.stage())
                        .map(RoadmapEntry::getProblemId).collect(Collectors.toSet());
        List<RoadmapEntry> scopedRoadmapEntries = filter.stage() == null ? roadmapEntries
                : roadmapEntries.stream().filter(entry -> entry.getStage() == filter.stage()).toList();
        List<ProblemAttempt> dateScopedAttempts = filterAttemptsByDateRange(attempts, filter);
        List<ProblemAttempt> scopedAndDateScopedAttempts = scopedProblemIds == null ? dateScopedAttempts
                : dateScopedAttempts.stream().filter(attempt -> scopedProblemIds.contains(attempt.problemId())).toList();

        ProblemDashboard.CoreProgress coreProgress = buildCoreProgress(roadmapEntries, progressByProblemId, attemptsByProblemId);
        ProblemDashboard.QualityMetrics qualityMetrics = buildQualityMetrics(scopedRoadmapEntries, progressByProblemId,
                attemptsByProblemId, scopedProblemIds);
        ProblemDashboard.TimingInsights timingInsights = buildTimingInsights(scopedAndDateScopedAttempts);
        List<ProblemDashboard.TopicInsight> topicInsights = buildTopicInsights(problemsById, progressByProblemId,
                attemptsByProblemId, scopedProblemIds);
        ProblemDashboard.Recommendation recommendation = buildRecommendation();
        List<ProblemDashboard.ReflectionGap> reflectionGaps = buildReflectionGaps(problemsById, progressByProblemId);
        List<ProblemDashboard.UnfinishedAttempt> unfinishedAttempts = buildUnfinishedAttempts(problemsById);

        return new ProblemDashboard(coreProgress, qualityMetrics, timingInsights, topicInsights, recommendation,
                reflectionGaps, unfinishedAttempts);
    }

    public ProblemDashboard build() {
        return build(ProblemDashboardFilter.empty());
    }

    private List<ProblemAttempt> filterAttemptsByDateRange(List<ProblemAttempt> attempts, ProblemDashboardFilter filter) {
        if (filter.fromDate() == null && filter.toDate() == null) {
            return attempts;
        }
        return attempts.stream().filter(attempt -> {
            LocalDate submittedDate = attempt.submittedAt().toLocalDate();
            if (filter.fromDate() != null && submittedDate.isBefore(filter.fromDate())) {
                return false;
            }
            return filter.toDate() == null || !submittedDate.isAfter(filter.toDate());
        }).toList();
    }

    // ---- Core progress ----------------------------------------------------------------------

    static ProblemDashboard.CoreProgress buildCoreProgress(List<RoadmapEntry> roadmapEntriesInOrder,
                                                            Map<Long, ProblemProgress> progressByProblemId,
                                                            Map<Long, List<ProblemAttempt>> attemptsByProblemId) {
        RoadmapEntry frontier = roadmapEntriesInOrder.stream()
                .filter(entry -> stateOf(entry.getProblemId(), progressByProblemId) != ProblemState.SOLVED)
                .findFirst().orElse(null);
        boolean roadmapComplete = frontier == null && !roadmapEntriesInOrder.isEmpty();

        MandatoryOptionalCounts overall = computeMandatoryOptionalCounts(roadmapEntriesInOrder, progressByProblemId);

        List<ProblemDashboard.StageProgress> stageBreakdown = new ArrayList<>();
        for (RoadmapStage stage : RoadmapStage.values()) {
            List<RoadmapEntry> inStage = roadmapEntriesInOrder.stream().filter(entry -> entry.getStage() == stage).toList();
            long solved = inStage.stream().filter(entry -> stateOf(entry.getProblemId(), progressByProblemId) == ProblemState.SOLVED).count();
            stageBreakdown.add(new ProblemDashboard.StageProgress(stage, inStage.size(), (int) solved));
        }

        ProblemDashboard.StatusBreakdown statusBreakdown = buildStatusBreakdown(roadmapEntriesInOrder, progressByProblemId, attemptsByProblemId);
        List<ProblemDashboard.WeeklyCount> weeklyCounts = buildWeeklySolvedCounts(progressByProblemId.values(), LocalDate.now());

        return new ProblemDashboard.CoreProgress(frontier == null ? null : frontier.getStage(),
                frontier == null ? null : frontier.getSetNumber(), roadmapComplete,
                overall.mandatoryTotal(), overall.mandatoryCompleted(), overall.optionalTotal(), overall.optionalCompleted(),
                stageBreakdown, statusBreakdown, weeklyCounts);
    }

    private record MandatoryOptionalCounts(int mandatoryTotal, int mandatoryCompleted, int optionalTotal, int optionalCompleted) {
    }

    static MandatoryOptionalCounts computeMandatoryOptionalCounts(List<RoadmapEntry> roadmapEntries,
                                                                   Map<Long, ProblemProgress> progressByProblemId) {
        int mandatoryTotal = 0;
        int mandatoryCompleted = 0;
        int optionalTotal = 0;
        int optionalCompleted = 0;
        for (RoadmapEntry entry : roadmapEntries) {
            boolean solved = stateOf(entry.getProblemId(), progressByProblemId) == ProblemState.SOLVED;
            if (entry.isMandatory()) {
                mandatoryTotal++;
                if (solved) {
                    mandatoryCompleted++;
                }
            } else {
                optionalTotal++;
                if (solved) {
                    optionalCompleted++;
                }
            }
        }
        return new MandatoryOptionalCounts(mandatoryTotal, mandatoryCompleted, optionalTotal, optionalCompleted);
    }

    /**
     * One bucket per distinct roadmap problem (a problem appearing in more than one stage is still
     * counted once here, unlike the per-position {@code stageBreakdown} above). See
     * {@link ProblemDashboard.StatusBreakdown} for the AC/ACX/CS split rationale.
     */
    static ProblemDashboard.StatusBreakdown buildStatusBreakdown(List<RoadmapEntry> roadmapEntries,
                                                                  Map<Long, ProblemProgress> progressByProblemId,
                                                                  Map<Long, List<ProblemAttempt>> attemptsByProblemId) {
        Set<Long> distinctProblemIds = new LinkedHashSet<>();
        roadmapEntries.forEach(entry -> distinctProblemIds.add(entry.getProblemId()));

        int ac = 0;
        int acx = 0;
        int cs = 0;
        int inProgress = 0;
        int notStarted = 0;
        for (Long problemId : distinctProblemIds) {
            ProblemState state = stateOf(problemId, progressByProblemId);
            switch (state) {
                case SOLVED -> {
                    if (isSolvedViaAcx(problemId, attemptsByProblemId)) {
                        acx++;
                    } else {
                        ac++;
                    }
                }
                case NEEDS_REVISIT -> cs++;
                case IN_PROGRESS -> inProgress++;
                case NOT_STARTED -> notStarted++;
            }
        }
        return new ProblemDashboard.StatusBreakdown(ac, acx, cs, inProgress, notStarted);
    }

    private static boolean isSolvedViaAcx(long problemId, Map<Long, List<ProblemAttempt>> attemptsByProblemId) {
        List<ProblemAttempt> attempts = attemptsByProblemId.getOrDefault(problemId, List.of());
        if (attempts.isEmpty()) {
            return false;
        }
        ProblemAttempt latest = attempts.stream().max(Comparator.comparingInt(ProblemAttempt::attemptNumber)).orElseThrow();
        return latest.submissionResult() == SubmissionResult.ACX;
    }

    /** Weeks (Monday-start, ISO) with at least one solve, for the trailing 8-week window ending in
     *  the week containing {@code today} — zero-filled so every week appears even with no solves. */
    static List<ProblemDashboard.WeeklyCount> buildWeeklySolvedCounts(Iterable<ProblemProgress> progressRows, LocalDate today) {
        Map<LocalDate, Integer> countsByWeekStart = new HashMap<>();
        for (ProblemProgress progress : progressRows) {
            if (progress.getState() != ProblemState.SOLVED || progress.getCompletedAt() == null) {
                continue;
            }
            LocalDate weekStart = weekStartOf(progress.getCompletedAt().toLocalDate());
            countsByWeekStart.merge(weekStart, 1, Integer::sum);
        }
        LocalDate currentWeekStart = weekStartOf(today);
        List<ProblemDashboard.WeeklyCount> result = new ArrayList<>();
        for (int weeksAgo = 7; weeksAgo >= 0; weeksAgo--) {
            LocalDate weekStart = currentWeekStart.minusWeeks(weeksAgo);
            result.add(new ProblemDashboard.WeeklyCount(weekStart, countsByWeekStart.getOrDefault(weekStart, 0)));
        }
        return result;
    }

    private static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static ProblemState stateOf(long problemId, Map<Long, ProblemProgress> progressByProblemId) {
        ProblemProgress progress = progressByProblemId.get(problemId);
        return progress == null ? ProblemState.NOT_STARTED : progress.getState();
    }

    // ---- Quality metrics ----------------------------------------------------------------------

    static ProblemDashboard.QualityMetrics buildQualityMetrics(List<RoadmapEntry> scopedRoadmapEntries,
                                                                Map<Long, ProblemProgress> progressByProblemId,
                                                                Map<Long, List<ProblemAttempt>> attemptsByProblemId,
                                                                Set<Long> scopedProblemIds) {
        Set<Long> scopedIds = scopedProblemIds != null ? scopedProblemIds
                : scopedRoadmapEntries.stream().map(RoadmapEntry::getProblemId).collect(Collectors.toSet());

        int firstSubmissionSample = 0;
        int firstSubmissionAccurate = 0;
        int acceptedSample = 0;
        int totalSubmissionsOnAccepted = 0;
        for (Long problemId : scopedIds) {
            List<ProblemAttempt> attempts = attemptsByProblemId.getOrDefault(problemId, List.of());
            if (attempts.isEmpty()) {
                continue;
            }
            firstSubmissionSample++;
            if (ProblemAttemptService.isFirstSubmissionAccurate(attempts)) {
                firstSubmissionAccurate++;
            }
            if (stateOf(problemId, progressByProblemId) == ProblemState.SOLVED) {
                acceptedSample++;
                totalSubmissionsOnAccepted += attempts.size();
            }
        }

        int independenceSample = 0;
        int independentCount = 0;
        int editorialCount = 0;
        int perceivedDifficultySample = 0;
        double perceivedDifficultySum = 0;
        for (Long problemId : scopedIds) {
            ProblemProgress progress = progressByProblemId.get(problemId);
            if (progress == null) {
                continue;
            }
            if (progress.getPerceivedDifficultyRating() != null) {
                perceivedDifficultySample++;
                perceivedDifficultySum += progress.getPerceivedDifficultyRating();
            }
            if (progress.getState() == ProblemState.SOLVED && progress.getSolvedWith() != null) {
                independenceSample++;
                if (progress.getSolvedWith() == SolvedWith.SELF) {
                    independentCount++;
                } else if (progress.getSolvedWith() == SolvedWith.EDITORIAL) {
                    editorialCount++;
                }
            }
        }

        MandatoryOptionalCounts mandatoryOptional = computeMandatoryOptionalCounts(scopedRoadmapEntries, progressByProblemId);

        return new ProblemDashboard.QualityMetrics(
                firstSubmissionSample, firstSubmissionSample == 0 ? 0.0 : firstSubmissionAccurate * 100.0 / firstSubmissionSample,
                independenceSample, independenceSample == 0 ? 0.0 : independentCount * 100.0 / independenceSample,
                independenceSample == 0 ? 0.0 : editorialCount * 100.0 / independenceSample,
                acceptedSample, acceptedSample == 0 ? 0.0 : totalSubmissionsOnAccepted * 1.0 / acceptedSample,
                perceivedDifficultySample, perceivedDifficultySample == 0 ? 0.0 : perceivedDifficultySum / perceivedDifficultySample,
                mandatoryOptional.mandatoryTotal(), mandatoryOptional.mandatoryCompleted(),
                mandatoryOptional.optionalTotal(), mandatoryOptional.optionalCompleted());
    }

    // ---- Timing insights ----------------------------------------------------------------------

    static ProblemDashboard.TimingInsights buildTimingInsights(List<ProblemAttempt> attempts) {
        long readingSum = 0;
        long thinkingSum = 0;
        long codingSum = 0;
        long debuggingSum = 0;
        int readingCount = 0;
        int thinkingCount = 0;
        int codingCount = 0;
        int debuggingCount = 0;
        int sampleCount = 0;

        for (ProblemAttempt attempt : attempts) {
            boolean hasAnyTiming = attempt.readingTimeSeconds() != null || attempt.thinkingTimeSeconds() != null
                    || attempt.codingTimeSeconds() != null || attempt.debuggingTimeSeconds() != null;
            if (!hasAnyTiming) {
                continue;
            }
            sampleCount++;
            if (attempt.readingTimeSeconds() != null) {
                readingSum += attempt.readingTimeSeconds();
                readingCount++;
            }
            if (attempt.thinkingTimeSeconds() != null) {
                thinkingSum += attempt.thinkingTimeSeconds();
                thinkingCount++;
            }
            if (attempt.codingTimeSeconds() != null) {
                codingSum += attempt.codingTimeSeconds();
                codingCount++;
            }
            if (attempt.debuggingTimeSeconds() != null) {
                debuggingSum += attempt.debuggingTimeSeconds();
                debuggingCount++;
            }
        }

        SolvingPhase bottleneck = null;
        long maxTotal = 0;
        Map<SolvingPhase, Long> totalsByPhase = new LinkedHashMap<>();
        totalsByPhase.put(SolvingPhase.READING, readingSum);
        totalsByPhase.put(SolvingPhase.THINKING, thinkingSum);
        totalsByPhase.put(SolvingPhase.CODING, codingSum);
        totalsByPhase.put(SolvingPhase.DEBUGGING, debuggingSum);
        for (Map.Entry<SolvingPhase, Long> phaseTotal : totalsByPhase.entrySet()) {
            if (phaseTotal.getValue() > maxTotal) {
                maxTotal = phaseTotal.getValue();
                bottleneck = phaseTotal.getKey();
            }
        }

        return new ProblemDashboard.TimingInsights(sampleCount,
                readingCount == 0 ? 0.0 : readingSum / (double) readingCount,
                thinkingCount == 0 ? 0.0 : thinkingSum / (double) thinkingCount,
                codingCount == 0 ? 0.0 : codingSum / (double) codingCount,
                debuggingCount == 0 ? 0.0 : debuggingSum / (double) debuggingCount,
                readingSum + thinkingSum + codingSum + debuggingSum, bottleneck);
    }

    // ---- Topic insights ----------------------------------------------------------------------

    static List<ProblemDashboard.TopicInsight> buildTopicInsights(Map<Long, Problem> problemsById,
                                                                   Map<Long, ProblemProgress> progressByProblemId,
                                                                   Map<Long, List<ProblemAttempt>> attemptsByProblemId,
                                                                   Set<Long> scopedProblemIds) {
        Map<String, List<Long>> problemIdsByTopic = new LinkedHashMap<>();
        for (Problem problem : problemsById.values()) {
            if (scopedProblemIds != null && !scopedProblemIds.contains(problem.getId())) {
                continue;
            }
            ProblemProgress progress = progressByProblemId.get(problem.getId());
            boolean attempted = (progress != null && progress.getState() != ProblemState.NOT_STARTED)
                    || !attemptsByProblemId.getOrDefault(problem.getId(), List.of()).isEmpty();
            if (!attempted) {
                continue;
            }
            String topic = effectiveTopic(problem, progress);
            problemIdsByTopic.computeIfAbsent(topic, key -> new ArrayList<>()).add(problem.getId());
        }

        List<ProblemDashboard.TopicInsight> insights = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : problemIdsByTopic.entrySet()) {
            List<Long> problemIds = entry.getValue();
            int sampleCount = problemIds.size();
            long solvedCount = problemIds.stream().filter(id -> stateOf(id, progressByProblemId) == ProblemState.SOLVED).count();
            double accuracyPercent = sampleCount == 0 ? 0.0 : solvedCount * 100.0 / sampleCount;

            int independenceSample = 0;
            int independentCount = 0;
            for (Long problemId : problemIds) {
                ProblemProgress progress = progressByProblemId.get(problemId);
                if (progress != null && progress.getState() == ProblemState.SOLVED && progress.getSolvedWith() != null) {
                    independenceSample++;
                    if (progress.getSolvedWith() == SolvedWith.SELF) {
                        independentCount++;
                    }
                }
            }
            double independencePercent = independenceSample == 0 ? 0.0 : independentCount * 100.0 / independenceSample;

            ProblemDashboard.TopicCategory category = sampleCount < ProblemDashboard.MIN_SAMPLE_SIZE
                    ? ProblemDashboard.TopicCategory.INSUFFICIENT_SAMPLE
                    : accuracyPercent >= 80.0 ? ProblemDashboard.TopicCategory.STRONG
                    : accuracyPercent < 50.0 ? ProblemDashboard.TopicCategory.WEAK
                    : ProblemDashboard.TopicCategory.DEVELOPING;

            insights.add(new ProblemDashboard.TopicInsight(entry.getKey(), sampleCount, accuracyPercent, independencePercent, category));
        }

        return insights.stream()
                .sorted(Comparator.comparingInt(ProblemDashboard.TopicInsight::sampleCount).reversed()
                        .thenComparing(ProblemDashboard.TopicInsight::topic))
                .toList();
    }

    /** The learner's own self-reported topic (#146's {@code actualTopic}) takes precedence over the
     *  problem's catalog topic, since it reflects what technique the problem actually turned out to
     *  need rather than how it was originally filed. */
    private static String effectiveTopic(Problem problem, ProblemProgress progress) {
        if (progress != null && progress.getActualTopic() != null && !progress.getActualTopic().isBlank()) {
            return progress.getActualTopic().strip();
        }
        return problem.getTopic();
    }

    // ---- Recommendation ----------------------------------------------------------------------

    private ProblemDashboard.Recommendation buildRecommendation() {
        Optional<ProblemLibraryEntry> next = problemLibraryService.getNextRecommendedProblem();
        return next.map(entry -> new ProblemDashboard.Recommendation(next, describeRecommendation(entry)))
                .orElseGet(() -> new ProblemDashboard.Recommendation(Optional.empty(),
                        "Every roadmap problem is already solved — nothing left to recommend."));
    }

    static String describeRecommendation(ProblemLibraryEntry entry) {
        RoadmapEntry roadmapEntry = entry.roadmapEntry();
        if (roadmapEntry == null) {
            return "Next unsolved problem in Blind Order.";
        }
        String setPart = roadmapEntry.getSetNumber() == null ? "" : " Set " + roadmapEntry.getSetNumber() + ",";
        return "Next unsolved problem in Blind Order — Stage " + roadmapEntry.getStage() + "," + setPart
                + " position " + roadmapEntry.getSequenceOrder() + ".";
    }

    // ---- Reflection gaps / unfinished attempts --------------------------------------------------

    private List<ProblemDashboard.ReflectionGap> buildReflectionGaps(Map<Long, Problem> problemsById,
                                                                      Map<Long, ProblemProgress> progressByProblemId) {
        return buildReflectionGaps(problemsById, progressByProblemId.values());
    }

    /** Solved problems with no {@code solvedWith} recorded yet — the reflection form was never
     *  filled in. Oldest solve first, since that one has been waiting longest. */
    static List<ProblemDashboard.ReflectionGap> buildReflectionGaps(Map<Long, Problem> problemsById,
                                                                     Iterable<ProblemProgress> progressRows) {
        List<ProblemDashboard.ReflectionGap> gaps = new ArrayList<>();
        for (ProblemProgress progress : progressRows) {
            if (progress.getState() == ProblemState.SOLVED && progress.getSolvedWith() == null
                    && problemsById.containsKey(progress.getProblemId())) {
                gaps.add(new ProblemDashboard.ReflectionGap(problemsById.get(progress.getProblemId()), progress));
            }
        }
        return gaps.stream()
                .sorted(Comparator.comparing(gap -> gap.progress().getCompletedAt(), Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private List<ProblemDashboard.UnfinishedAttempt> buildUnfinishedAttempts(Map<Long, Problem> problemsById) {
        return buildUnfinishedAttempts(sessionRepository.findAllActive(), problemsById);
    }

    /** Sessions started but neither finished nor abandoned, stalest first — the ones most likely to
     *  be forgotten rather than genuinely in progress right now. */
    static List<ProblemDashboard.UnfinishedAttempt> buildUnfinishedAttempts(List<ProblemSolvingSession> activeSessions,
                                                                             Map<Long, Problem> problemsById) {
        return activeSessions.stream()
                .filter(session -> problemsById.containsKey(session.getProblemId()))
                .sorted(Comparator.comparing(ProblemSolvingSession::getLastActiveAt))
                .map(session -> new ProblemDashboard.UnfinishedAttempt(problemsById.get(session.getProblemId()), session))
                .toList();
    }
}
