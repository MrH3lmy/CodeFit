package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class QuestDiscoveryIsolationTest {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();

    @Test
    void allCohortsAreExcludedFromOrdinaryBrowsingSearchTopicsAndSuggestions() {
        var library = new ProblemLibraryService();
        assertTrue(
                library.getTopicBasedEntries().stream()
                        .allMatch(e -> ProblemVisibility.ordinary(e.problem())));
        assertTrue(
                library.getBlindOrderEntries().stream()
                        .allMatch(e -> ProblemVisibility.ordinary(e.problem())));
        assertFalse(library.getDistinctTopics().contains("Evolution"));
        assertFalse(library.getDistinctPlatforms().contains("CODEFIT_EVOLUTION"));
        assertTrue(new ProblemService().getProblemsByTopic("Evolution").isEmpty());
        assertTrue(
                new ProblemService()
                        .getAllProblems().stream().allMatch(ProblemVisibility::ordinary));
        for (var c : JavaTrainingPack.challenges()) {
            if (c.runner() != Runner.JAVA21_MAIN) continue;
            long id = catalog.transaction(db -> catalog.problemId(db, c.ref()));
            assertTrue(new ProblemService().findById(id).isEmpty());
            var p = new ProblemRepository().findById(id).orElseThrow();
            var entry =
                    new ProblemLibraryEntry(
                            p, null, com.codefit.model.ProblemProgress.notStarted(id));
            assertTrue(library.getNextRecommendedProblem(List.of(entry)).isEmpty());
            assertTrue(library.applyFilter(List.of(entry), ProblemLibraryFilter.empty()).isEmpty());
            assertTrue(library.getRevisitQueue(List.of(entry)).isEmpty());
        }
    }

    @Test
    void directWorkspaceAndDraftAndFlashcardRoutesFailClosedEvenAfterPresentation() {
        var service = new ChallengePresentationService();
        var view =
                service.present(
                        new ChallengePresentation.Request(
                                "route-guard",
                                new CatalogRef("broken-counter", 1),
                                new CatalogRef("counter-repair", 1),
                                Cohort.PRACTICE,
                                Familiarity.UNKNOWN));
        long id = view.backing().problemId();
        assertThrows(
                IllegalArgumentException.class,
                () -> com.codefit.ui.NavigationService.showSolvingWorkspace(id));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemGuidanceService()
                                .revealLevel(id, com.codefit.model.HintLevel.EXPLANATION));
        var workspace = new ProblemSolvingWorkspaceService();
        assertThrows(IllegalArgumentException.class, () -> workspace.loadWorkspace(id));
        assertThrows(IllegalArgumentException.class, () -> workspace.start(id));
        assertThrows(
                IllegalArgumentException.class, () -> workspace.markPreviouslySolved(id, "manual"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaSolutionWorkspaceService().loadDraft(id));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaSolutionWorkspaceService().listTestCases(id));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemFlashcardService()
                                .buildDraft(
                                        id, com.codefit.model.ReflectionCardSource.LESSON_LEARNED));
        assertTrue(
                new ProblemSolvingSessionRepository()
                        .findAllActive().stream().noneMatch(s -> s.getProblemId() == id));
    }

    @Test
    void weeklyAssessmentBankIsUnchangedUntilExplicitlyReserved() {
        var bank = new AssessmentItemRepository();
        var before = bank.findAll();
        assertFalse(before.isEmpty());
        var reserved = before.getFirst();
        catalog.transaction(
                c -> {
                    catalog.reserveAssessment(
                            c, reserved.getId(), new CatalogRef("baseline-trace", 1));
                    catalog.reserveAssessment(
                            c, reserved.getId(), new CatalogRef("baseline-trace", 1));
                    return null;
                });
        var after = bank.findAll();
        var selected = new WeeklyAssessmentSelectionService().selectWeeklyAssessment(1000);
        assertTrue(selected.stream().noneMatch(s -> s.item().getId() == reserved.getId()));
        assertTrue(
                selected.stream()
                        .allMatch(
                                s -> after.stream().anyMatch(i -> i.getId() == s.item().getId())));

        assertEquals(before.size() - 1, after.size());
        assertTrue(bank.findById(reserved.getId()).isEmpty());
        assertEquals(
                before.stream().skip(1).map(i -> i.getId()).toList(),
                after.stream().map(i -> i.getId()).toList());
        catalog.transaction(
                c -> {
                    try (var statement =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " assessment_attempts(assessment_item_id,variant_index,skill_category,module_name,correct,submitted_answer)"
                                        + " VALUES(?,0,'fixture','fixture',0,'private prior"
                                        + " response')")) {
                        statement.setLong(1, reserved.getId());
                        statement.executeUpdate();
                    }
                    return null;
                });
        var receipt =
                new ChallengePresentationService()
                        .present(
                                new ChallengePresentation.Request(
                                        "prior-bank-history",
                                        new CatalogRef("initial-diagnostic", 1),
                                        new CatalogRef("baseline-trace", 1),
                                        Cohort.BASELINE,
                                        Familiarity.UNKNOWN))
                        .receipt();
        assertTrue(
                receipt.prior().knownFamiliar(),
                "A reserved bank item's historical attempt prevents a fresh claim");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        catalog.transaction(
                                c -> {
                                    catalog.reserveAssessment(
                                            c,
                                            reserved.getId(),
                                            new CatalogRef("baseline-localize", 1));
                                    return null;
                                }));
    }
}
