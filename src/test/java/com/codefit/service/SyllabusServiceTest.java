package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Deck;
import com.codefit.model.SyllabusModule;
import com.codefit.repository.DeckRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers syllabus wiring for every registered training path. */
class SyllabusServiceTest {

    private final SyllabusService syllabusService = new SyllabusService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void javaBackendModulesStillReportsEightModulesInOrder() {
        List<SyllabusModule> modules = syllabusService.getJavaBackendModules();

        assertEquals(8, modules.size());
        for (int i = 0; i < modules.size(); i++) {
            assertEquals(i + 1, modules.get(i).getModuleNumber());
            assertEquals("Java Backend", modules.get(i).getPathName());
        }
    }

    @Test
    void advancedBackendEngineeringModulesReportsTenModulesInOrderWithObjectives() {
        List<SyllabusModule> modules = syllabusService.getAdvancedBackendEngineeringModules();

        assertEquals(10, modules.size());
        for (int i = 0; i < modules.size(); i++) {
            SyllabusModule module = modules.get(i);
            assertEquals(i + 1, module.getModuleNumber());
            assertEquals("Advanced Backend Engineering", module.getPathName());
            assertTrue(module.getTitle() != null && !module.getTitle().isBlank());
            assertTrue(module.getLearningObjective() != null && !module.getLearningObjective().isBlank());
        }
        assertEquals("Java Concurrency & Thread Safety", modules.get(0).getTitle());
        assertEquals("API & Database Failure Scenarios", modules.get(9).getTitle());
    }

    @Test
    void databaseInternalsModulesReportsFiveModulesInOrderWithObjectives() {
        List<SyllabusModule> modules = syllabusService.getDatabaseInternalsModules();

        assertEquals(5, modules.size());
        for (int i = 0; i < modules.size(); i++) {
            SyllabusModule module = modules.get(i);
            assertEquals(i + 1, module.getModuleNumber());
            assertEquals("Database Internals", module.getPathName());
            assertTrue(module.getTitle() != null && !module.getTitle().isBlank());
            assertTrue(module.getLearningObjective() != null && !module.getLearningObjective().isBlank());
        }
        assertEquals("Architecture, Layout & File Formats", modules.get(0).getTitle());
        assertEquals("Anti-Entropy, Transactions & Consensus", modules.get(4).getTitle());
    }

    @Test
    void allTrainingPathModulesConcatenatesThreePathsInRegistrationOrder() {
        List<SyllabusModule> all = syllabusService.getAllTrainingPathModules();

        assertEquals(23, all.size());
        assertEquals("Java Backend", all.get(0).getPathName());
        assertEquals("Advanced Backend Engineering", all.get(8).getPathName());
        assertEquals("Database Internals", all.get(18).getPathName());
    }

    /**
     * Creates (or reuses, if already present) the real "ABE 02" deck and a single never-reviewed
     * card, then verifies the syllabus reflects it via durable mastery rather than a raw card count.
     */
    @Test
    void advancedModuleReflectsARealMatchingDeck() {
        String deckName = "ABE 02 - Database Transactions, Locking & Isolation";
        String testFront = "TEST-FIXTURE: never-reviewed card used only by SyllabusServiceTest";

        DeckRepository deckRepository = new DeckRepository();
        FlashcardService flashcardService = new FlashcardService();

        Deck deck = deckRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(deckName))
                .findFirst()
                .orElseGet(() -> deckRepository.save(new Deck(deckName, "Test fixture deck for SyllabusServiceTest.")));

        if (!flashcardService.cardExistsInDeck(deck.getId(), testFront)) {
            flashcardService.addCard(deck.getId(), testFront, "irrelevant answer");
        }

        List<SyllabusModule> modules = syllabusService.getAdvancedBackendEngineeringModules();
        Optional<SyllabusModule> module2 = modules.stream()
                .filter(module -> module.getModuleNumber() == 2)
                .findFirst();

        assertTrue(module2.isPresent());
        SyllabusModule syllabusModule = module2.get();
        assertTrue(syllabusModule.getDeckId() > 0, "module should now resolve to a real deck id");
        assertTrue(syllabusModule.getEstimatedCardCount() >= 1);
        assertTrue(syllabusModule.getMasteredCardCount() <= syllabusModule.getEstimatedCardCount());
        assertTrue(syllabusModule.getMasteredCardCount() < syllabusModule.getEstimatedCardCount(),
                "the freshly added, never-reviewed fixture card should not already be mastered");
    }
}
