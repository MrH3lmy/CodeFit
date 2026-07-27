package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.RoadmapEntry;
import com.codefit.repository.RoadmapEntryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance regression coverage for #166 against the same approved, workbook-scale fixture
 * {@code RealJuniorTrainingSheetImportTest} uses (~926 roadmap memberships / 923 unique problems) —
 * large enough that the old per-row {@code findById}/{@code findByProblemId} N+1 loop in
 * {@link ProblemLibraryService} opened well over a thousand individual SQLite connections per call.
 *
 * <p>These assertions are deliberately wall-clock, not a mocked query counter: at this scale, the
 * bulk-query rewrite (three queries per view, see {@link ProblemLibraryService#getBlindOrderEntries()})
 * finishes in the tens of milliseconds, while the old N+1 approach took whole seconds even on fast
 * hardware — so a generous budget still fails loudly if a future change reintroduces a per-row query
 * inside these hot paths, without being flaky on slower CI hardware.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemLibraryScalePerformanceTest {

    private static final Path WORKBOOK_PATH = Paths.get("data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx");

    private final TrainingSheetImportService importService = new TrainingSheetImportService();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemLibraryService libraryService = new ProblemLibraryService();
    private final Set<Long> importBatchIdsToCleanUp = new LinkedHashSet<>();

    @BeforeAll
    void importTheApprovedWorkbook() throws Exception {
        DatabaseConfig.initialize();
        TrainingSheetImportSummary summary = importService.importWorkbook(WORKBOOK_PATH);
        importBatchIdsToCleanUp.add(summary.importBatchId());
    }

    @AfterAll
    void removeEveryRoadmapEntryThisClassImported() {
        for (Long batchId : importBatchIdsToCleanUp) {
            importService.deleteImportBatch(batchId);
        }
    }

    @Test
    void blindOrderStaysCorrectAndFastAtWorkbookScale() {
        long startNanos = System.nanoTime();
        List<ProblemLibraryEntry> blindOrder = libraryService.getBlindOrderEntries();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(blindOrder.size() >= 926, "at least the approved workbook's 926 roadmap memberships");
        assertTrue(elapsedMillis < 3000,
                "getBlindOrderEntries() took " + elapsedMillis + "ms at workbook scale — a per-row N+1 query "
                        + "regression would take vastly longer than a handful of bulk queries");
    }

    @Test
    void topicViewStaysCorrectAndFastAtWorkbookScale() {
        long startNanos = System.nanoTime();
        List<ProblemLibraryEntry> topicEntries = libraryService.getTopicBasedEntries();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        Set<Long> distinctProblemIds = topicEntries.stream().map(entry -> entry.problem().getId()).collect(Collectors.toSet());
        assertEquals(topicEntries.size(), distinctProblemIds.size(), "Topics view lists each problem exactly once");
        assertTrue(topicEntries.size() >= 923, "at least the approved workbook's 923 unique problems");
        assertTrue(elapsedMillis < 3000,
                "getTopicBasedEntries() took " + elapsedMillis + "ms at workbook scale — a per-row N+1 query "
                        + "regression would take vastly longer than a handful of bulk queries");
    }

    @Test
    void topicViewPrimaryMembershipMatchesBlindOrderEarliestStagePerProblem() {
        // The bulk-query rewrite picks the "primary" roadmap membership for the Topics view by
        // scanning the already-loaded, roadmap-ordered entries in memory (see #166) instead of a
        // per-problem findByProblemId(...).stream().findFirst() query — this proves that shortcut
        // still lands on the exact same earliest-stage membership the old per-row lookup would.
        List<ProblemLibraryEntry> topicEntries = libraryService.getTopicBasedEntries();
        List<RoadmapEntry> allEntries = roadmapEntryRepository.findAllInRoadmapOrder();

        int checked = 0;
        for (ProblemLibraryEntry entry : topicEntries) {
            if (checked >= 200) {
                break;
            }
            Optional<RoadmapEntry> expectedPrimary = allEntries.stream()
                    .filter(candidate -> candidate.getProblemId() == entry.problem().getId())
                    .findFirst();
            if (expectedPrimary.isEmpty()) {
                assertNull(entry.roadmapEntry());
            } else {
                assertEquals(expectedPrimary.get().getStage(), entry.roadmapEntry().getStage());
                assertEquals(expectedPrimary.get().getSequenceOrder(), entry.roadmapEntry().getSequenceOrder());
            }
            checked++;
        }
        assertTrue(checked > 0, "the workbook fixture must actually produce rows to check");
    }
}
