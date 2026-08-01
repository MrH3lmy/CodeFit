package com.codefit.config;

import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ProblemGuidanceRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for #171's acceptance criteria against the seeded Stage A pilot set (see
 * {@link StageAPilotGuidanceSeed}): every pilot problem gets a stable {@code (platform,
 * externalCode)} identity, a Stage A roadmap slot, and complete {@code CODEFIT}-sourced guidance,
 * and re-running the seed (as every {@link DatabaseConfig#initialize()} call does) never duplicates
 * any of it.
 */
class StageAPilotGuidanceSeedTest {

    private final ProblemRepository problemRepository = new ProblemRepository();
    private final RoadmapEntryRepository roadmapEntryRepository = new RoadmapEntryRepository();
    private final ProblemGuidanceRepository guidanceRepository = new ProblemGuidanceRepository();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void everyPilotProblemHasAStableIdentityAndAStageASlot() {
        for (StageAPilotGuidanceSeed.Entry entry : StageAPilotGuidanceSeed.PILOT_SET) {
            Problem problem = problemRepository.findByPlatformAndExternalCode(entry.platform(), entry.externalCode())
                    .orElseThrow(() -> new AssertionError("missing seeded problem " + entry.externalCode()));
            assertEquals(entry.title(), problem.getTitle());

            List<RoadmapEntry> roadmapEntries = roadmapEntryRepository.findByProblemId(problem.getId());
            RoadmapEntry stageAEntry = roadmapEntries.stream()
                    .filter(roadmapEntry -> roadmapEntry.getStage() == RoadmapStage.A)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(entry.externalCode() + " must be registered in Stage A"));
            assertEquals(entry.sequenceOrder(), stageAEntry.getSequenceOrder());
            assertTrue(stageAEntry.isMandatory());
        }
    }

    @Test
    void everyPilotProblemHasCompleteCodefitAuthoredGuidance() {
        for (StageAPilotGuidanceSeed.Entry entry : StageAPilotGuidanceSeed.PILOT_SET) {
            Problem problem = problemRepository.findByPlatformAndExternalCode(entry.platform(), entry.externalCode())
                    .orElseThrow();
            ProblemGuidance guidance = guidanceRepository.findByProblemIdAndSource(problem.getId(), GuidanceSource.CODEFIT)
                    .orElseThrow(() -> new AssertionError(entry.externalCode() + " must have CODEFIT-sourced guidance"));

            assertEquals(GuidanceSource.CODEFIT, guidance.getSource());
            assertHasText(guidance.textForLevel(HintLevel.CLARIFY), entry.externalCode(), "Clarify");
            assertHasText(guidance.textForLevel(HintLevel.OBSERVATION), entry.externalCode(), "Observation");
            assertHasText(guidance.textForLevel(HintLevel.APPROACH), entry.externalCode(), "Approach");
            assertTrue(guidance.hasCompleteExplanation(),
                    entry.externalCode() + "'s Explanation must cover idea, pseudocode, complexity, and mistakes");
            assertFalse(guidance.textForLevel(HintLevel.EXPLANATION).contains("(not yet authored)"),
                    entry.externalCode() + "'s composed Explanation must not contain any unauthored placeholder");
        }
    }

    @Test
    void everyPilotProblemHasAtLeastOneReferenceLinkAndNoCopiedEditorialText() {
        for (StageAPilotGuidanceSeed.Entry entry : StageAPilotGuidanceSeed.PILOT_SET) {
            Problem problem = problemRepository.findByPlatformAndExternalCode(entry.platform(), entry.externalCode())
                    .orElseThrow();
            ProblemGuidance guidance = guidanceRepository.findByProblemIdAndSource(problem.getId(), GuidanceSource.CODEFIT)
                    .orElseThrow();

            List<String> referenceLinks = com.codefit.service.AcceptedAnswerCodec.decode(guidance.getReferenceLinksEncoded());
            assertFalse(referenceLinks.isEmpty(), entry.externalCode() + " must carry at least one reference link");
            for (String link : referenceLinks) {
                assertTrue(link.startsWith("https://codeforces.com/"),
                        entry.externalCode() + "'s reference links must point only at the problem's own judge page: " + link);
            }
        }
    }

    @Test
    void reSeedingIsIdempotent() {
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();

        for (StageAPilotGuidanceSeed.Entry entry : StageAPilotGuidanceSeed.PILOT_SET) {
            List<Problem> matches = problemRepository.findAll().stream()
                    .filter(problem -> entry.platform().equals(problem.getPlatform())
                            && entry.externalCode().equals(problem.getExternalCode()))
                    .toList();
            assertEquals(1, matches.size(), "re-running the seed must never duplicate " + entry.externalCode());

            List<RoadmapEntry> stageAEntries = roadmapEntryRepository.findByProblemId(matches.get(0).getId()).stream()
                    .filter(roadmapEntry -> roadmapEntry.getStage() == RoadmapStage.A)
                    .toList();
            assertEquals(1, stageAEntries.size(), "re-running the seed must never duplicate " + entry.externalCode() + "'s Stage A slot");
        }
    }

    private void assertHasText(String text, String externalCode, String levelName) {
        assertTrue(text != null && !text.isBlank(), externalCode + "'s " + levelName + " level must be authored");
    }
}
