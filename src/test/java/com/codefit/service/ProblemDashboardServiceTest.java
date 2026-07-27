package com.codefit.service;

import com.codefit.model.ComplexityClass;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.FinalCategory;
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
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProblemDashboardService}'s pure aggregation logic (#147): every method under
 * test here takes plain lists/maps and returns a result, with no database involved, so every metric's
 * math is verified directly and independent of import/workspace fixtures elsewhere in the suite.
 */
class ProblemDashboardServiceTest {

    // ---- fixture builders -------------------------------------------------------------------

    private static long nextId = 1;

    private Problem problem(String topic) {
        long id = nextId++;
        return new Problem(id, "EXT-" + id, "TEST-PLATFORM", "Problem " + id, "https://example.test/" + id,
                topic, null, null, null, null);
    }

    private RoadmapEntry entry(long problemId, RoadmapStage stage, int sequenceOrder, boolean mandatory) {
        return new RoadmapEntry(problemId, stage, sequenceOrder, null, mandatory, DifficultyLevel.MEDIUM);
    }

    private ProblemProgress progress(long problemId, ProblemState state) {
        return new ProblemProgress(0, problemId, state, null, null, null, null, null,
                null, null, null, null, null, false, false, false, false, null, null);
    }

    private ProblemProgress progress(long problemId, ProblemState state, Integer difficultyRating, SolvedWith solvedWith,
                                      LocalDateTime completedAt) {
        return new ProblemProgress(0, problemId, state, difficultyRating, solvedWith, null, null, null,
                null, null, null, null, null, false, false, false, false, completedAt, null);
    }

    private ProblemProgress progressWithTopic(long problemId, ProblemState state, String actualTopic, SolvedWith solvedWith) {
        return new ProblemProgress(0, problemId, state, null, solvedWith, null, null, null,
                null, null, null, null, actualTopic, false, false, false, false, null, null);
    }

    private ProblemAttempt attempt(long problemId, int attemptNumber, SubmissionResult result, Integer reading,
                                    Integer thinking, Integer coding, Integer debugging) {
        return new ProblemAttempt(attemptNumber, problemId, attemptNumber, result, reading, thinking, coding, debugging,
                LocalDateTime.now(), null);
    }

    // ---- buildCoreProgress --------------------------------------------------------------------

    @Test
    void frontierIsTheFirstUnsolvedEntryInRoadmapOrder() {
        long p1 = 1, p2 = 2, p3 = 3;
        List<RoadmapEntry> entries = List.of(entry(p1, RoadmapStage.A, 1, true), entry(p2, RoadmapStage.A, 2, true),
                entry(p3, RoadmapStage.B, 1, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(p1, progress(p1, ProblemState.SOLVED),
                p2, progress(p2, ProblemState.IN_PROGRESS));

        ProblemDashboard.CoreProgress core = ProblemDashboardService.buildCoreProgress(entries, progressByProblemId, Map.of());

        assertEquals(RoadmapStage.A, core.currentStage());
        assertFalse(core.roadmapComplete());
    }

    @Test
    void roadmapCompleteWhenEveryEntryIsSolved() {
        long p1 = 1;
        List<RoadmapEntry> entries = List.of(entry(p1, RoadmapStage.A, 1, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(p1, progress(p1, ProblemState.SOLVED));

        ProblemDashboard.CoreProgress core = ProblemDashboardService.buildCoreProgress(entries, progressByProblemId, Map.of());

        assertTrue(core.roadmapComplete());
        assertNull(core.currentStage());
    }

    @Test
    void mandatoryAndOptionalCountsSplitByTheRoadmapEntryFlag() {
        long p1 = 1, p2 = 2, p3 = 3;
        List<RoadmapEntry> entries = List.of(entry(p1, RoadmapStage.A, 1, true), entry(p2, RoadmapStage.A, 2, true),
                entry(p3, RoadmapStage.A, 3, false));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(p1, progress(p1, ProblemState.SOLVED));

        ProblemDashboard.CoreProgress core = ProblemDashboardService.buildCoreProgress(entries, progressByProblemId, Map.of());

        assertEquals(2, core.mandatoryTotal());
        assertEquals(1, core.mandatoryCompleted());
        assertEquals(1, core.mandatoryRemaining());
        assertEquals(1, core.optionalTotal());
        assertEquals(0, core.optionalCompleted());
    }

    @Test
    void stageBreakdownCoversEveryDeclaredStageEvenWithNoEntries() {
        long p1 = 1;
        List<RoadmapEntry> entries = List.of(entry(p1, RoadmapStage.A, 1, true));

        ProblemDashboard.CoreProgress core = ProblemDashboardService.buildCoreProgress(entries, Map.of(), Map.of());

        assertEquals(RoadmapStage.values().length, core.stageBreakdown().size());
        assertEquals(1, core.stageBreakdown().get(0).total());
        assertEquals(0, core.stageBreakdown().get(1).total());
    }

    @Test
    void statusBreakdownSplitsSolvedProblemsIntoAcAndAcxByTheirLatestAttempt() {
        long acProblem = 1, acxProblem = 2, needsRevisit = 3, inProgress = 4, notStarted = 5;
        List<RoadmapEntry> entries = List.of(entry(acProblem, RoadmapStage.A, 1, true), entry(acxProblem, RoadmapStage.A, 2, true),
                entry(needsRevisit, RoadmapStage.A, 3, true), entry(inProgress, RoadmapStage.A, 4, true),
                entry(notStarted, RoadmapStage.A, 5, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                acProblem, progress(acProblem, ProblemState.SOLVED),
                acxProblem, progress(acxProblem, ProblemState.SOLVED),
                needsRevisit, progress(needsRevisit, ProblemState.NEEDS_REVISIT),
                inProgress, progress(inProgress, ProblemState.IN_PROGRESS));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = Map.of(
                acProblem, List.of(attempt(acProblem, 1, SubmissionResult.AC, 60, 60, 60, 60)),
                acxProblem, List.of(attempt(acxProblem, 1, SubmissionResult.WA, 60, 60, 60, 60),
                        attempt(acxProblem, 2, SubmissionResult.ACX, 60, 60, 60, 60)));

        ProblemDashboard.StatusBreakdown breakdown = ProblemDashboardService.buildStatusBreakdown(entries, progressByProblemId, attemptsByProblemId);

        assertEquals(1, breakdown.acCount());
        assertEquals(1, breakdown.acxCount());
        assertEquals(1, breakdown.couldNotSolveCount());
        assertEquals(1, breakdown.inProgressCount());
        assertEquals(1, breakdown.notStartedCount());
        assertEquals(5, breakdown.total());
    }

    @Test
    void solvedWithNoAttemptsAtAllDefaultsToTheAcBucket() {
        long imported = 1;
        List<RoadmapEntry> entries = List.of(entry(imported, RoadmapStage.A, 1, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(imported, progress(imported, ProblemState.SOLVED));

        ProblemDashboard.StatusBreakdown breakdown = ProblemDashboardService.buildStatusBreakdown(entries, progressByProblemId, Map.of());

        assertEquals(1, breakdown.acCount());
        assertEquals(0, breakdown.acxCount());
    }

    @Test
    void weeklySolvedCountsAreZeroFilledAcrossTheTrailingEightWeeks() {
        LocalDate today = LocalDate.of(2026, 7, 27); // a Monday
        LocalDateTime solvedThisWeek = today.atStartOfDay().plusHours(2);
        LocalDateTime solvedThreeWeeksAgo = today.minusWeeks(3).atStartOfDay().plusHours(2);
        List<ProblemProgress> progressRows = List.of(
                progress(1, ProblemState.SOLVED, null, null, solvedThisWeek),
                progress(2, ProblemState.SOLVED, null, null, solvedThreeWeeksAgo),
                progress(3, ProblemState.IN_PROGRESS, null, null, null));

        List<ProblemDashboard.WeeklyCount> weeks = ProblemDashboardService.buildWeeklySolvedCounts(progressRows, today);

        assertEquals(8, weeks.size());
        assertEquals(today, weeks.get(7).weekStart());
        assertEquals(1, weeks.get(7).solvedCount());
        assertEquals(1, weeks.get(4).solvedCount());
        assertEquals(0, weeks.get(0).solvedCount());
    }

    // ---- buildQualityMetrics ------------------------------------------------------------------

    @Test
    void firstSubmissionAccuracyCountsProblemsWhoseFirstAttemptSucceeded() {
        long accurate = 1, inaccurate = 2;
        List<RoadmapEntry> entries = List.of(entry(accurate, RoadmapStage.A, 1, true), entry(inaccurate, RoadmapStage.A, 2, true));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = Map.of(
                accurate, List.of(attempt(accurate, 1, SubmissionResult.AC, null, null, null, null)),
                inaccurate, List.of(attempt(inaccurate, 1, SubmissionResult.WA, null, null, null, null),
                        attempt(inaccurate, 2, SubmissionResult.AC, null, null, null, null)));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, Map.of(), attemptsByProblemId, null);

        assertEquals(2, quality.firstSubmissionSampleCount());
        assertEquals(50.0, quality.firstSubmissionAccuracyPercent());
    }

    @Test
    void independentAndEditorialRatesAreShareOfSolvedProblemsWithKnownAssistance() {
        long self1 = 1, self2 = 2, editorial = 3, hint = 4;
        List<RoadmapEntry> entries = List.of(entry(self1, RoadmapStage.A, 1, true), entry(self2, RoadmapStage.A, 2, true),
                entry(editorial, RoadmapStage.A, 3, true), entry(hint, RoadmapStage.A, 4, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                self1, progress(self1, ProblemState.SOLVED, null, SolvedWith.SELF, null),
                self2, progress(self2, ProblemState.SOLVED, null, SolvedWith.SELF, null),
                editorial, progress(editorial, ProblemState.SOLVED, null, SolvedWith.EDITORIAL, null),
                hint, progress(hint, ProblemState.SOLVED, null, SolvedWith.HINT, null));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, progressByProblemId, Map.of(), null);

        assertEquals(4, quality.independenceSampleCount());
        assertEquals(50.0, quality.independentSolveRatePercent());
        assertEquals(25.0, quality.editorialDependencyRatePercent());
    }

    @Test
    void averageSubmissionsPerAcceptedOnlyCountsSolvedProblemsWithAttempts() {
        long solvedInOne = 1, solvedInThree = 2, stillOpen = 3;
        List<RoadmapEntry> entries = List.of(entry(solvedInOne, RoadmapStage.A, 1, true), entry(solvedInThree, RoadmapStage.A, 2, true),
                entry(stillOpen, RoadmapStage.A, 3, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                solvedInOne, progress(solvedInOne, ProblemState.SOLVED),
                solvedInThree, progress(solvedInThree, ProblemState.SOLVED),
                stillOpen, progress(stillOpen, ProblemState.IN_PROGRESS));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = Map.of(
                solvedInOne, List.of(attempt(solvedInOne, 1, SubmissionResult.AC, null, null, null, null)),
                solvedInThree, List.of(attempt(solvedInThree, 1, SubmissionResult.WA, null, null, null, null),
                        attempt(solvedInThree, 2, SubmissionResult.WA, null, null, null, null),
                        attempt(solvedInThree, 3, SubmissionResult.AC, null, null, null, null)));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, progressByProblemId, attemptsByProblemId, null);

        assertEquals(2, quality.acceptedSampleCount());
        assertEquals(2.0, quality.averageSubmissionsPerAccepted());
    }

    @Test
    void averagePerceivedDifficultyOnlyCountsRatedProblems() {
        long rated1 = 1, rated2 = 2, unrated = 3;
        List<RoadmapEntry> entries = List.of(entry(rated1, RoadmapStage.A, 1, true), entry(rated2, RoadmapStage.A, 2, true),
                entry(unrated, RoadmapStage.A, 3, true));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                rated1, progress(rated1, ProblemState.SOLVED, 4, null, null),
                rated2, progress(rated2, ProblemState.SOLVED, 8, null, null),
                unrated, progress(unrated, ProblemState.SOLVED));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, progressByProblemId, Map.of(), null);

        assertEquals(2, quality.perceivedDifficultySampleCount());
        assertEquals(6.0, quality.averagePerceivedDifficulty());
    }

    @Test
    void belowMinimumSampleSizeSignalFlagsReportNoSignalYet() {
        long onlyOne = 1;
        List<RoadmapEntry> entries = List.of(entry(onlyOne, RoadmapStage.A, 1, true));
        Map<Long, List<ProblemAttempt>> attemptsByProblemId = Map.of(
                onlyOne, List.of(attempt(onlyOne, 1, SubmissionResult.AC, null, null, null, null)));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, Map.of(), attemptsByProblemId, null);

        assertFalse(quality.hasFirstSubmissionSignal());
    }

    @Test
    void mandatoryAndOptionalCompletionPercentAreComputedFromScopedRoadmapEntries() {
        long mandatorySolved = 1, mandatoryOpen = 2, optionalSolved = 3;
        List<RoadmapEntry> entries = List.of(entry(mandatorySolved, RoadmapStage.A, 1, true), entry(mandatoryOpen, RoadmapStage.A, 2, true),
                entry(optionalSolved, RoadmapStage.A, 3, false));
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                mandatorySolved, progress(mandatorySolved, ProblemState.SOLVED),
                optionalSolved, progress(optionalSolved, ProblemState.SOLVED));

        ProblemDashboard.QualityMetrics quality = ProblemDashboardService.buildQualityMetrics(entries, progressByProblemId, Map.of(), null);

        assertEquals(50.0, quality.mandatoryCompletionPercent());
        assertEquals(100.0, quality.optionalCompletionPercent());
    }

    // ---- buildTimingInsights ------------------------------------------------------------------

    @Test
    void timingAveragesAreComputedPerPhaseIndependently() {
        List<ProblemAttempt> attempts = List.of(
                attempt(1, 1, SubmissionResult.AC, 100, 200, 300, 40),
                attempt(2, 1, SubmissionResult.AC, 200, null, 300, 80));

        ProblemDashboard.TimingInsights timing = ProblemDashboardService.buildTimingInsights(attempts);

        assertTrue(timing.hasSignal());
        assertEquals(2, timing.sampleCount());
        assertEquals(150.0, timing.averageReadingSeconds());
        assertEquals(200.0, timing.averageThinkingSeconds());
        assertEquals(300.0, timing.averageCodingSeconds());
        assertEquals(60.0, timing.averageDebuggingSeconds());
        assertEquals(100 + 200 + 300 + 40 + 200 + 300 + 80, timing.totalSolvingSeconds());
    }

    @Test
    void bottleneckPhaseIsWhicheverPhaseAccumulatedTheMostTotalTime() {
        List<ProblemAttempt> attempts = List.of(attempt(1, 1, SubmissionResult.AC, 60, 120, 900, 30));

        ProblemDashboard.TimingInsights timing = ProblemDashboardService.buildTimingInsights(attempts);

        assertEquals(SolvingPhase.CODING, timing.bottleneckPhase());
    }

    @Test
    void bottleneckTieIsBrokenByDeclaredPhaseOrder() {
        List<ProblemAttempt> attempts = List.of(attempt(1, 1, SubmissionResult.AC, 100, 100, 100, 100));

        ProblemDashboard.TimingInsights timing = ProblemDashboardService.buildTimingInsights(attempts);

        assertEquals(SolvingPhase.READING, timing.bottleneckPhase());
    }

    @Test
    void noTimingSignalWhenNoAttemptHasAnyRecordedTime() {
        List<ProblemAttempt> attempts = List.of(attempt(1, 1, SubmissionResult.AC, null, null, null, null));

        ProblemDashboard.TimingInsights timing = ProblemDashboardService.buildTimingInsights(attempts);

        assertFalse(timing.hasSignal());
        assertNull(timing.bottleneckPhase());
    }

    // ---- buildTopicInsights -------------------------------------------------------------------

    @Test
    void topicGroupingPrefersTheActualTopicOverTheCatalogTopicWhenSet() {
        Problem catalogedAsArrays = problem("Arrays");
        Map<Long, Problem> problemsById = Map.of(catalogedAsArrays.getId(), catalogedAsArrays);
        Map<Long, ProblemProgress> progressByProblemId = Map.of(catalogedAsArrays.getId(),
                progressWithTopic(catalogedAsArrays.getId(), ProblemState.SOLVED, "Two Pointers", SolvedWith.SELF));

        List<ProblemDashboard.TopicInsight> insights = ProblemDashboardService.buildTopicInsights(problemsById, progressByProblemId, Map.of(), null);

        assertEquals(1, insights.size());
        assertEquals("Two Pointers", insights.get(0).topic());
    }

    @Test
    void topicsBelowMinimumSampleAreInsufficientSample() {
        Problem onlyProblem = problem("Graphs");
        Map<Long, Problem> problemsById = Map.of(onlyProblem.getId(), onlyProblem);
        Map<Long, ProblemProgress> progressByProblemId = Map.of(onlyProblem.getId(), progress(onlyProblem.getId(), ProblemState.SOLVED));

        List<ProblemDashboard.TopicInsight> insights = ProblemDashboardService.buildTopicInsights(problemsById, progressByProblemId, Map.of(), null);

        assertEquals(ProblemDashboard.TopicCategory.INSUFFICIENT_SAMPLE, insights.get(0).category());
    }

    @Test
    void topicsAreCategorizedStrongDevelopingOrWeakByAccuracy() {
        Map<Long, Problem> problemsById = new java.util.HashMap<>();
        Map<Long, ProblemProgress> progressByProblemId = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            Problem p = problem("Strong Topic");
            problemsById.put(p.getId(), p);
            progressByProblemId.put(p.getId(), progress(p.getId(), i < 4 ? ProblemState.SOLVED : ProblemState.IN_PROGRESS));
        }
        for (int i = 0; i < 5; i++) {
            Problem p = problem("Weak Topic");
            problemsById.put(p.getId(), p);
            progressByProblemId.put(p.getId(), progress(p.getId(), i < 1 ? ProblemState.SOLVED : ProblemState.IN_PROGRESS));
        }

        List<ProblemDashboard.TopicInsight> insights = ProblemDashboardService.buildTopicInsights(problemsById, progressByProblemId, Map.of(), null);

        Map<String, ProblemDashboard.TopicInsight> byTopic = insights.stream()
                .collect(Collectors.toMap(ProblemDashboard.TopicInsight::topic, insight -> insight));
        assertEquals(ProblemDashboard.TopicCategory.STRONG, byTopic.get("Strong Topic").category());
        assertEquals(ProblemDashboard.TopicCategory.WEAK, byTopic.get("Weak Topic").category());
    }

    @Test
    void scopedProblemIdsExcludeProblemsOutsideTheStageFilter() {
        Problem inScope = problem("Trees");
        Problem outOfScope = problem("Trees");
        Map<Long, Problem> problemsById = Map.of(inScope.getId(), inScope, outOfScope.getId(), outOfScope);
        Map<Long, ProblemProgress> progressByProblemId = Map.of(
                inScope.getId(), progress(inScope.getId(), ProblemState.SOLVED),
                outOfScope.getId(), progress(outOfScope.getId(), ProblemState.SOLVED));

        List<ProblemDashboard.TopicInsight> insights = ProblemDashboardService.buildTopicInsights(problemsById, progressByProblemId,
                Map.of(), Set.of(inScope.getId()));

        assertEquals(1, insights.size());
        assertEquals(1, insights.get(0).sampleCount());
    }

    // ---- recommendation / reflection gaps / unfinished attempts -------------------------------

    @Test
    void recommendationExplanationCitesStageSetAndPosition() {
        Problem p = problem("Arrays");
        RoadmapEntry roadmapEntry = new RoadmapEntry(0, p.getId(), RoadmapStage.C1, 7, 2, true, DifficultyLevel.HARD, null);
        ProblemLibraryEntry entry = new ProblemLibraryEntry(p, roadmapEntry, ProblemProgress.notStarted(p.getId()));

        String reason = ProblemDashboardService.describeRecommendation(entry);

        assertTrue(reason.contains("Stage C1"));
        assertTrue(reason.contains("Set 2"));
        assertTrue(reason.contains("position 7"));
    }

    @Test
    void reflectionGapsOnlyIncludeSolvedProblemsMissingSolvedWith() {
        Problem missingReflection = problem("Graphs");
        Problem hasReflection = problem("Graphs");
        Problem stillOpen = problem("Graphs");
        Map<Long, Problem> problemsById = Map.of(missingReflection.getId(), missingReflection,
                hasReflection.getId(), hasReflection, stillOpen.getId(), stillOpen);
        List<ProblemProgress> progressRows = List.of(
                progress(missingReflection.getId(), ProblemState.SOLVED, null, null, null),
                progress(hasReflection.getId(), ProblemState.SOLVED, null, SolvedWith.SELF, null),
                progress(stillOpen.getId(), ProblemState.IN_PROGRESS));

        List<ProblemDashboard.ReflectionGap> gaps = ProblemDashboardService.buildReflectionGaps(problemsById, progressRows);

        assertEquals(1, gaps.size());
        assertEquals(missingReflection.getId(), gaps.get(0).problem().getId());
    }

    @Test
    void unfinishedAttemptsAreSortedStalestFirst() {
        Problem stale = problem("Arrays");
        Problem fresh = problem("Arrays");
        Map<Long, Problem> problemsById = Map.of(stale.getId(), stale, fresh.getId(), fresh);
        ProblemSolvingSession staleSession = ProblemSolvingSession.start(stale.getId());
        staleSession.setLastActiveAt(LocalDateTime.now().minusDays(5));
        ProblemSolvingSession freshSession = ProblemSolvingSession.start(fresh.getId());
        freshSession.setLastActiveAt(LocalDateTime.now());

        List<ProblemDashboard.UnfinishedAttempt> unfinished = ProblemDashboardService.buildUnfinishedAttempts(
                List.of(freshSession, staleSession), problemsById);

        assertEquals(stale.getId(), unfinished.get(0).problem().getId());
        assertEquals(fresh.getId(), unfinished.get(1).problem().getId());
    }
}
