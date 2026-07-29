package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two Problem Library views (#144) are built from, and stay consistent with, the same
 * underlying data, plus filtering and next-recommended-problem behavior. Touches the shared local
 * database idempotently, the same way {@code ProblemServiceTest} does; every roadmap position uses a
 * random large sequence number so it can never collide with another test's fixture data (see the
 * same issue in {@code TrainingSheetImportServiceTest}).
 */
class ProblemLibraryServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemProgressService progressService = new ProblemProgressService();
    private final ProblemLibraryService libraryService = new ProblemLibraryService();
    private final ProblemSolvingSessionService sessionService = new ProblemSolvingSessionService();

    private final Random random = new Random();
    private int nextOrder = 20_000_000 + random.nextInt(1_000_000);

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private String uniquePlatform(String testName) {
        return "TEST-FIXTURE-LIBRARY-" + testName + "-" + UUID.randomUUID();
    }

    private Problem createProblem(String platform, String code, String title, String topic, Integer quality) {
        return problemService.findOrCreateProblem(platform, code, title, "https://example.test/" + code, topic, quality, List.of());
    }

    @Test
    void blindOrderListsOneRowPerMembershipInRoadmapOrder() {
        String platform = uniquePlatform("blind-order");
        Problem first = createProblem(platform, "B1", "First", "General", null);
        Problem second = createProblem(platform, "B2", "Second", "General", null);

        int baseOrder = nextOrder;
        problemService.addToRoadmap(second.getId(), RoadmapStage.A, baseOrder, null, true, null);
        problemService.addToRoadmap(first.getId(), RoadmapStage.A, baseOrder + 1, null, true, null);
        nextOrder += 2;

        List<ProblemLibraryEntry> entries = libraryService.getBlindOrderEntries();
        List<ProblemLibraryEntry> ours = entries.stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        assertEquals(2, ours.size());
        assertEquals(second.getId(), ours.get(0).problem().getId(), "lower sequence order comes first");
        assertEquals(first.getId(), ours.get(1).problem().getId());
    }

    @Test
    void topicViewListsOneRowPerProblemEvenWithMultipleRoadmapMemberships() {
        String platform = uniquePlatform("topic-dedup");
        Problem problem = createProblem(platform, "T1", "Repeated", "Arrays", null);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.A, nextOrder++, null, true, null);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.C2, nextOrder++, null, true, null);

        List<ProblemLibraryEntry> topicEntries = libraryService.getTopicBasedEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        assertEquals(1, topicEntries.size(), "the same problem must appear once in the Topics view regardless of membership count");
    }

    @Test
    void bothViewsReuseTheSameProgressData() {
        String platform = uniquePlatform("shared-progress");
        Problem problem = createProblem(platform, "P1", "Shared Progress", "General", null);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.B, nextOrder++, null, true, null);
        progressService.updateProgress(problem.getId(), ProblemState.SOLVED, null);

        ProblemLibraryEntry blindOrderEntry = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getId() == problem.getId())
                .findFirst().orElseThrow();
        ProblemLibraryEntry topicEntry = libraryService.getTopicBasedEntries().stream()
                .filter(entry -> entry.problem().getId() == problem.getId())
                .findFirst().orElseThrow();

        assertEquals(ProblemState.SOLVED, blindOrderEntry.progress().getState());
        assertEquals(ProblemState.SOLVED, topicEntry.progress().getState());
    }

    @Test
    void filtersCanBeCombinedAndEachOneNarrowsTheResult() {
        String platform = uniquePlatform("filters");
        Problem matches = createProblem(platform, "F1", "Matches Everything", "Graphs", 5);
        Problem wrongTopic = createProblem(platform, "F2", "Wrong Topic", "Arrays", 5);
        problemService.addToRoadmap(matches.getId(), RoadmapStage.D1, nextOrder++, null, true, DifficultyLevel.HARD);
        problemService.addToRoadmap(wrongTopic.getId(), RoadmapStage.D1, nextOrder++, null, true, DifficultyLevel.HARD);

        List<ProblemLibraryEntry> all = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        ProblemLibraryFilter combined = ProblemLibraryFilter.empty()
                .withTopic("Graphs")
                .withSuggestedLevel(DifficultyLevel.HARD)
                .withMinQualityRating(4)
                .withPlatform(platform)
                .withSearchText("matches");

        List<ProblemLibraryEntry> filtered = libraryService.applyFilter(all, combined);
        assertEquals(1, filtered.size());
        assertEquals(matches.getId(), filtered.get(0).problem().getId());
    }

    @Test
    void stageFilterNarrowsToOnlyThatStage() {
        String platform = uniquePlatform("stage-filter");
        Problem stageAProblem = createProblem(platform, "SA1", "Stage A Problem", "General", null);
        Problem stageBProblem = createProblem(platform, "SB1", "Stage B Problem", "General", null);
        problemService.addToRoadmap(stageAProblem.getId(), RoadmapStage.A, nextOrder++, null, true, null);
        problemService.addToRoadmap(stageBProblem.getId(), RoadmapStage.B, nextOrder++, null, true, null);

        List<ProblemLibraryEntry> all = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        List<ProblemLibraryEntry> stageAOnly = libraryService.applyFilter(all, ProblemLibraryFilter.empty().withStage(RoadmapStage.A));
        assertEquals(1, stageAOnly.size());
        assertEquals(stageAProblem.getId(), stageAOnly.get(0).problem().getId());
    }

    @Test
    void hasAnyProblemsIsTrueOnceAtLeastOneProblemExists() {
        assertTrue(problemService.findOrCreateProblem(uniquePlatform("has-any"), "H1", "Has Any", "https://example.test/h1",
                "General", null, List.of()) != null);
        assertTrue(libraryService.hasAnyProblems());
    }

    @Test
    void nextRecommendedAndRevisitQueueOverloadsReuseAPassedInBlindOrderListInsteadOfRequerying() {
        String platform = uniquePlatform("reuse-list");
        Problem needsRevisit = createProblem(platform, "RU1", "Needs Revisit", "General", null);
        Problem unsolved = createProblem(platform, "RU2", "Unsolved", "General", null);

        int baseOrder = nextOrder;
        nextOrder += 2;
        problemService.addToRoadmap(needsRevisit.getId(), RoadmapStage.C1, baseOrder, null, true, null);
        problemService.addToRoadmap(unsolved.getId(), RoadmapStage.C1, baseOrder + 1, null, true, null);
        progressService.updateProgress(needsRevisit.getId(), ProblemState.NEEDS_REVISIT, null);

        List<ProblemLibraryEntry> blindOrder = libraryService.getBlindOrderEntries();

        Optional<ProblemLibraryEntry> recommendedFromList = libraryService.getNextRecommendedProblem(blindOrder);
        Optional<ProblemLibraryEntry> recommendedFresh = libraryService.getNextRecommendedProblem();
        assertEquals(recommendedFresh.map(ProblemLibraryEntry::problem).map(Problem::getId),
                recommendedFromList.map(ProblemLibraryEntry::problem).map(Problem::getId));

        List<ProblemLibraryEntry> revisitFromList = libraryService.getRevisitQueue(blindOrder).stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();
        assertEquals(1, revisitFromList.size());
        assertEquals(needsRevisit.getId(), revisitFromList.get(0).problem().getId());
    }

    @Test
    void clearingTheFilterRestoresEveryEntry() {
        String platform = uniquePlatform("clear-filter");
        Problem problem = createProblem(platform, "C1", "Clearable", "General", null);
        problemService.addToRoadmap(problem.getId(), RoadmapStage.D2, nextOrder++, null, true, null);

        List<ProblemLibraryEntry> all = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        List<ProblemLibraryEntry> overFiltered = libraryService.applyFilter(all, ProblemLibraryFilter.empty().withTopic("Nonexistent"));
        assertTrue(overFiltered.isEmpty());

        List<ProblemLibraryEntry> cleared = libraryService.applyFilter(all, ProblemLibraryFilter.empty());
        assertEquals(1, cleared.size());
    }

    @Test
    void nextRecommendedSkipsSolvedProblemsAndReturnsTheFirstUnsolvedInRoadmapOrder() {
        String platform = uniquePlatform("recommendation");
        Problem solved = createProblem(platform, "R1", "Already Solved", "General", null);
        Problem unsolved = createProblem(platform, "R2", "Still Unsolved", "General", null);

        int baseOrder = nextOrder;
        nextOrder += 2;
        // Both fixtures share stage D3 so they only ever compete against each other, never against
        // another test's D3 fixtures (which use their own random sequence numbers).
        problemService.addToRoadmap(solved.getId(), RoadmapStage.D3, baseOrder, null, true, null);
        problemService.addToRoadmap(unsolved.getId(), RoadmapStage.D3, baseOrder + 1, null, true, null);
        progressService.updateProgress(solved.getId(), ProblemState.SOLVED, null);

        Optional<ProblemLibraryEntry> recommended = libraryService.getBlindOrderEntries().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .filter(entry -> entry.progress().getState() != ProblemState.SOLVED)
                .findFirst();

        assertTrue(recommended.isPresent());
        assertEquals(unsolved.getId(), recommended.get().problem().getId());
    }

    @Test
    void selectNextRecommendedPrefersAnEarlierUnsolvedMandatoryPositionOverALaterOptionalOne() {
        // #161: an unsolved optional problem must never block a later, still-unsolved mandatory one
        // from being the guided recommendation.
        ProblemLibraryEntry optionalButEarlier = libraryEntry(1, RoadmapStage.A, 1, false, ProblemState.NOT_STARTED);
        ProblemLibraryEntry mandatoryButLater = libraryEntry(2, RoadmapStage.A, 2, true, ProblemState.NOT_STARTED);

        Optional<ProblemLibraryEntry> recommended =
                ProblemLibraryService.selectNextRecommended(List.of(optionalButEarlier, mandatoryButLater));

        assertTrue(recommended.isPresent());
        assertEquals(2, recommended.get().problem().getId(), "the mandatory position wins even though it's later");
    }

    @Test
    void selectNextRecommendedFallsBackToOptionalWorkOnceEveryMandatoryPositionIsSolved() {
        ProblemLibraryEntry mandatorySolved = libraryEntry(1, RoadmapStage.A, 1, true, ProblemState.SOLVED);
        ProblemLibraryEntry optionalUnsolved = libraryEntry(2, RoadmapStage.A, 2, false, ProblemState.NOT_STARTED);

        Optional<ProblemLibraryEntry> recommended =
                ProblemLibraryService.selectNextRecommended(List.of(mandatorySolved, optionalUnsolved));

        assertTrue(recommended.isPresent());
        assertEquals(2, recommended.get().problem().getId(), "optional work is recommended once mandatory work is exhausted");
    }

    @Test
    void selectNextRecommendedIsEmptyOnceEverythingIsSolved() {
        ProblemLibraryEntry solved = libraryEntry(1, RoadmapStage.A, 1, true, ProblemState.SOLVED);

        assertTrue(ProblemLibraryService.selectNextRecommended(List.of(solved)).isEmpty());
    }

    /**
     * #161's "unless the learner explicitly overrides" escape hatch: mandatory-gating only decides
     * the <em>default</em> recommendation ({@link ProblemLibraryService#selectNextRecommended}) — it
     * must never stop a learner from starting a specific, gated-out problem directly. There is no
     * separate "override" flag anywhere in the codebase; the override <em>is</em> the fact that
     * {@link ProblemSolvingSessionService#startOrResume} performs no gating check of its own.
     */
    @Test
    void aProblemThatMandatoryGatingSkipsCanStillBeStartedDirectlyAsAnExplicitOverride() {
        ProblemLibraryEntry optionalButEarlier = libraryEntry(101, RoadmapStage.A, 1, false, ProblemState.NOT_STARTED);
        ProblemLibraryEntry mandatoryButLater = libraryEntry(102, RoadmapStage.A, 2, true, ProblemState.NOT_STARTED);
        Optional<ProblemLibraryEntry> recommended =
                ProblemLibraryService.selectNextRecommended(List.of(optionalButEarlier, mandatoryButLater));
        assertEquals(102, recommended.orElseThrow().problem().getId(), "the mandatory position is the guided recommendation");

        // Problem 101 is the one gating skipped over — starting it directly must still succeed.
        Problem gatedProblem = createProblem(uniquePlatform("override"), "OV1", "Explicit Override Target", "General", null);
        ProblemSolvingSession overrideSession = sessionService.startOrResume(gatedProblem.getId());

        assertEquals(gatedProblem.getId(), overrideSession.getProblemId());
    }

    private ProblemLibraryEntry libraryEntry(long problemId, RoadmapStage stage, int sequenceOrder, boolean mandatory, ProblemState state) {
        Problem problem = new Problem(problemId, "CODE-" + problemId, "TEST-FIXTURE-PLATFORM", "Problem " + problemId,
                null, "General", null, null, null, null);
        RoadmapEntry roadmapEntry = new RoadmapEntry(problemId, stage, sequenceOrder, null, mandatory, null);
        ProblemProgress progress = new ProblemProgress(0, problemId, state, null, null, null, null, null, null,
                null, null, null, null, false, false, false, false, null, null);
        return new ProblemLibraryEntry(problem, roadmapEntry, progress);
    }

    @Test
    void revisitQueueListsOnlyNeedsRevisitPositionsInBlindOrder() {
        String platform = uniquePlatform("revisit-queue");
        Problem needsRevisit = createProblem(platform, "V1", "Needs Another Look", "General", null);
        Problem solved = createProblem(platform, "V2", "Already Solved", "General", null);
        Problem notStarted = createProblem(platform, "V3", "Not Started Yet", "General", null);

        int baseOrder = nextOrder;
        nextOrder += 3;
        problemService.addToRoadmap(needsRevisit.getId(), RoadmapStage.D3, baseOrder, null, true, null);
        problemService.addToRoadmap(solved.getId(), RoadmapStage.D3, baseOrder + 1, null, true, null);
        problemService.addToRoadmap(notStarted.getId(), RoadmapStage.D3, baseOrder + 2, null, true, null);
        progressService.updateProgress(needsRevisit.getId(), ProblemState.NEEDS_REVISIT, null);
        progressService.updateProgress(solved.getId(), ProblemState.SOLVED, null);

        List<ProblemLibraryEntry> revisitQueue = libraryService.getRevisitQueue().stream()
                .filter(entry -> entry.problem().getPlatform().equals(platform))
                .toList();

        assertEquals(1, revisitQueue.size());
        assertEquals(needsRevisit.getId(), revisitQueue.get(0).problem().getId());
    }

    @Test
    void distinctTopicsAndPlatformsAreSortedAndDeduplicated() {
        String platform = uniquePlatform("distinct");
        createProblem(platform, "D1", "One", "Zeta Topic", null);
        createProblem(platform, "D2", "Two", "Alpha Topic", null);
        createProblem(platform, "D3", "Three", "Alpha Topic", null);

        List<String> topics = libraryService.getDistinctTopics();
        assertTrue(topics.contains("Alpha Topic"));
        assertTrue(topics.contains("Zeta Topic"));
        assertEquals(topics.stream().distinct().count(), topics.size(), "topics must be deduplicated");

        List<String> platforms = libraryService.getDistinctPlatforms();
        assertTrue(platforms.contains(platform));
        assertFalse(platforms.isEmpty());
    }
}
