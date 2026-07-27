package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
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
