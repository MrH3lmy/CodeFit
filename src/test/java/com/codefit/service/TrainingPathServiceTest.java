package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.TrainingPath;
import com.codefit.model.TrainingPath.TrainingPathModule;
import com.codefit.service.TrainingPathService.TrainingPathAction;
import com.codefit.service.TrainingPathService.TrainingPathModuleProgress;
import com.codefit.service.TrainingPathService.TrainingPathRecommendation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPathServiceTest {

    private final TrainingPathService trainingPathService = new TrainingPathService();

    private Deck deck(String name) {
        Deck deck = new Deck(name, "test deck");
        deck.setId(1);
        return deck;
    }

    @Test
    void bothTrainingPathsAreRegisteredWithDistinctNames() {
        List<TrainingPath> paths = trainingPathService.getTrainingPaths();

        assertEquals(2, paths.size());
        assertEquals(Set.of("Java Backend", "Advanced Backend Engineering"),
                paths.stream().map(TrainingPath::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void advancedBackendEngineeringPathHasTenSequentiallyOrderedModules() {
        TrainingPath path = trainingPathService.getAdvancedBackendEngineeringPath();
        List<TrainingPathModule> modules = path.getModules();

        assertEquals(10, modules.size());
        for (int i = 0; i < modules.size(); i++) {
            assertEquals(i + 1, modules.get(i).getOrder(), "modules should be sorted 1..10 by order");
        }
    }

    @Test
    void everyModuleBeyondTheFirstDeclaresAtLeastOnePrerequisiteAndTheFirstDeclaresNone() {
        List<TrainingPathModule> modules = trainingPathService.getAdvancedBackendEngineeringPath().getModules();

        TrainingPathModule first = modules.get(0);
        assertTrue(first.getPrerequisiteModuleOrders().isEmpty(),
                "the first module in a path is foundational and should have no prerequisites");
        assertFalse(first.hasPrerequisites());

        for (TrainingPathModule module : modules.subList(1, modules.size())) {
            assertTrue(module.hasPrerequisites(),
                    () -> "module " + module.getOrder() + " (" + module.getTitle() + ") should declare a prerequisite");
            for (int prerequisiteOrder : module.getPrerequisiteModuleOrders()) {
                assertTrue(prerequisiteOrder < module.getOrder(),
                        () -> "module " + module.getOrder() + " lists a prerequisite that is not an earlier module");
                assertTrue(prerequisiteOrder >= 1 && prerequisiteOrder <= modules.size(),
                        () -> "module " + module.getOrder() + " lists prerequisite order " + prerequisiteOrder
                                + " which does not correspond to any module in the path");
            }
        }
    }

    @Test
    void everyModuleInBothPathsHasASensibleMasteryThreshold() {
        for (TrainingPath path : trainingPathService.getTrainingPaths()) {
            for (TrainingPathModule module : path.getModules()) {
                assertTrue(module.getMasteryThreshold() > 0.0 && module.getMasteryThreshold() <= 1.0,
                        () -> path.getName() + " module " + module.getOrder() + " has an out-of-range mastery threshold: "
                                + module.getMasteryThreshold());
            }
        }
    }

    @Test
    void concurrencyModuleMatchesAllFourJavaConcurrencyDecksByName() {
        TrainingPathModule concurrencyModule = trainingPathService.getAdvancedBackendEngineeringPath()
                .findModuleByOrder(1).orElseThrow();

        assertEquals(4, concurrencyModule.getDeckNames().size());
        assertTrue(concurrencyModule.matchesDeck(deck("JCIP 01 - Fundamentals")));
        assertTrue(concurrencyModule.matchesDeck(deck("jcip 04 - locks, atomics & memory model")),
                "deck name matching should be case-insensitive");
        assertFalse(concurrencyModule.matchesDeck(deck("ABE 02 - Database Transactions, Locking & Isolation")));
        assertFalse(concurrencyModule.matchesDeck(null));
    }

    @Test
    void findModuleForDeckAggregatesAcrossAnyOfAModulesDecks() {
        TrainingPath path = trainingPathService.getAdvancedBackendEngineeringPath();

        assertTrue(path.findModuleForDeck(deck("JCIP 02 - Task Execution & Cancellation")).isPresent());
        assertTrue(path.findModuleForDeck(deck("ABE 06 - OAuth2, OIDC & Service Authentication")).isPresent());
        assertEquals(Optional.empty(), path.findModuleForDeck(deck("Some Unrelated Deck")));
    }

    // --- Pure recommendation-logic tests against TrainingPathService.recommend, which is
    // deliberately separated from database access (mirrors MasteryService.evaluate). ---

    private TrainingPathModule module(int order, List<Integer> prerequisites, double masteryThreshold) {
        return new TrainingPathModule(order, "Module " + order, "objective", "Deck " + order, prerequisites, masteryThreshold);
    }

    private TrainingPathModuleProgress progress(TrainingPathModule module, int cardCount, long dueCount, int progressPercent) {
        return new TrainingPathModuleProgress(module, deck(module.getDeckName()), cardCount, dueCount, progressPercent);
    }

    @Test
    void noRecommendationWhenPathHasNoMatchingDecksYet() {
        TrainingPath path = trainingPathService.getAdvancedBackendEngineeringPath();
        assertEquals(Optional.empty(), TrainingPathService.recommend(path, List.of()));
    }

    @Test
    void emptyStarterModuleWithinLimitIsRecommendedOverAnythingElse() {
        TrainingPathModule module1 = module(1, List.of(), 0.8);
        TrainingPathModule module2 = module(2, List.of(1), 0.8);
        TrainingPath path = new TrainingPath("Test Path", List.of(module1, module2),
                java.util.regex.Pattern.compile("^NEVER MATCHES$"), 2, 0.8);

        List<TrainingPathModuleProgress> progressList = List.of(
                progress(module1, 5, 3, 40),
                progress(module2, 0, 0, 0));

        Optional<TrainingPathRecommendation> recommendation = TrainingPathService.recommend(path, progressList);

        assertTrue(recommendation.isPresent());
        assertEquals(TrainingPathAction.ADD_STARTER_CARDS, recommendation.get().action());
        assertEquals(2, recommendation.get().current().module().getOrder());
    }

    @Test
    void weakestDueModuleIsRecommendedWhenNoStarterGapExists() {
        TrainingPathModule module1 = module(1, List.of(), 0.8);
        TrainingPathModule module2 = module(2, List.of(1), 0.8);
        TrainingPath path = new TrainingPath("Test Path", List.of(module1, module2),
                java.util.regex.Pattern.compile("^NEVER MATCHES$"), 0, 0.8);

        // module1 is further along (70%) but still has due cards; module2 is weaker (20%) and due.
        List<TrainingPathModuleProgress> progressList = List.of(
                progress(module1, 10, 2, 70),
                progress(module2, 10, 5, 20));

        Optional<TrainingPathRecommendation> recommendation = TrainingPathService.recommend(path, progressList);

        assertTrue(recommendation.isPresent());
        assertEquals(TrainingPathAction.REVIEW_DUE_MODULE, recommendation.get().action());
        assertEquals(2, recommendation.get().current().module().getOrder(),
                "the module with lower mastered-percent progress should be reviewed first");
    }

    @Test
    void moveToNextModuleUsesEachModulesOwnMasteryThresholdNotAFlatPercentage() {
        // module1 requires a strict 90% mastery threshold; module2 only needs 70%.
        TrainingPathModule strictModule = module(1, List.of(), 0.9);
        TrainingPathModule lenientModule = module(2, List.of(1), 0.7);
        TrainingPath path = new TrainingPath("Test Path", List.of(strictModule, lenientModule),
                java.util.regex.Pattern.compile("^NEVER MATCHES$"), 0, 0.8);

        // 85% mastered clears the old flat 80% path-level bar, but not this module's own 90% bar.
        List<TrainingPathModuleProgress> belowOwnThreshold = List.of(
                progress(strictModule, 10, 0, 85),
                progress(lenientModule, 10, 0, 60));
        assertEquals(Optional.empty(), TrainingPathService.recommend(path, belowOwnThreshold),
                "85% mastered should not satisfy a module whose own threshold is 90%");

        // Once the strict module actually clears its own 90% bar, it should be recommended to move on.
        List<TrainingPathModuleProgress> clearsOwnThreshold = List.of(
                progress(strictModule, 10, 0, 92),
                progress(lenientModule, 10, 0, 60));
        Optional<TrainingPathRecommendation> recommendation = TrainingPathService.recommend(path, clearsOwnThreshold);

        assertTrue(recommendation.isPresent());
        assertEquals(TrainingPathAction.MOVE_TO_NEXT_MODULE, recommendation.get().action());
        assertEquals(1, recommendation.get().current().module().getOrder());
        assertEquals(2, recommendation.get().next().module().getOrder());
    }
}
