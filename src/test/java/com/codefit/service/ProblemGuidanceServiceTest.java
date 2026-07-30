package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.SolvedWith;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the progressive hint ladder (#162): revealing one level at a time in order, persisting the
 * highest level opened per attempt on the session (and it resetting for a new attempt), guidance
 * provenance/editing, missing-guidance handling, and the hint-to-assistance-level mapping.
 */
class ProblemGuidanceServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final ProblemGuidanceService guidanceService = new ProblemGuidanceService();
    private final ProblemSolvingSessionService sessionService = new ProblemSolvingSessionService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private Problem fixtureProblem(String code) {
        return problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", code, "Guidance Fixture " + code,
                null, "General", null, List.of());
    }

    @Test
    void openingHintsRevealsOneLevelAtATimeInOrder() {
        Problem problem = fixtureProblem("TF-162-ORDER");
        sessionService.reset(problem.getId());
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT,
                "Restate: find two indices summing to target.", "Sorted arrays let you use two pointers.",
                "Two-pointer scan from both ends.", "Idea: shrink the window from both ends.",
                "lo = 0; hi = n - 1; while lo < hi: ...", "O(n) time, O(1) space.", "Off-by-one at the boundary.",
                null, null);

        ProblemGuidanceService.HintReveal first = guidanceService.openNextHintLevel(problem.getId());
        assertEquals(HintLevel.CLARIFY, first.level());
        assertTrue(first.hasContent());
        assertEquals("Restate: find two indices summing to target.", first.text());

        ProblemGuidanceService.HintReveal second = guidanceService.openNextHintLevel(problem.getId());
        assertEquals(HintLevel.OBSERVATION, second.level());

        ProblemGuidanceService.HintReveal third = guidanceService.openNextHintLevel(problem.getId());
        assertEquals(HintLevel.APPROACH, third.level());

        ProblemGuidanceService.HintReveal fourth = guidanceService.openNextHintLevel(problem.getId());
        assertEquals(HintLevel.EXPLANATION, fourth.level());

        ProblemGuidanceService.HintReveal againAtTop = guidanceService.openNextHintLevel(problem.getId());
        assertEquals(HintLevel.EXPLANATION, againAtTop.level());
    }

    @Test
    void theHighestOpenedLevelPersistsOnTheSessionAndIsQueryableDirectly() {
        Problem problem = fixtureProblem("TF-162-PERSIST");
        sessionService.reset(problem.getId());
        assertTrue(guidanceService.getOpenedLevel(problem.getId()).isEmpty(), "no session yet means no hint opened yet");

        guidanceService.openNextHintLevel(problem.getId());
        guidanceService.openNextHintLevel(problem.getId());

        Optional<HintLevel> opened = guidanceService.getOpenedLevel(problem.getId());
        assertEquals(Optional.of(HintLevel.OBSERVATION), opened);
    }

    @Test
    void aNewAttemptResetsTheOpenedHintLevel() {
        Problem problem = fixtureProblem("TF-162-RESET");
        sessionService.reset(problem.getId());
        guidanceService.openNextHintLevel(problem.getId());
        guidanceService.openNextHintLevel(problem.getId());
        assertEquals(Optional.of(HintLevel.OBSERVATION), guidanceService.getOpenedLevel(problem.getId()));

        sessionService.reset(problem.getId());

        assertTrue(guidanceService.getOpenedLevel(problem.getId()).isEmpty(), "a fresh attempt has no hint opened yet");
    }

    @Test
    void openingALevelWithNoAuthoredTextReportsMissingContentRatherThanFabricatingIt() {
        Problem problem = fixtureProblem("TF-162-MISSING");
        sessionService.reset(problem.getId());

        ProblemGuidanceService.HintReveal reveal = guidanceService.openNextHintLevel(problem.getId());

        assertEquals(HintLevel.CLARIFY, reveal.level());
        assertFalse(reveal.hasContent());
        assertNull(reveal.text());
    }

    @Test
    void savingGuidanceTwiceEditsInPlaceRatherThanCreatingASecondRow() {
        Problem problem = fixtureProblem("TF-162-EDIT");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER, "first draft", null, null, null, null, null, null, null, null);

        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER, "improved draft", "now with an observation",
                null, null, null, null, null, List.of("Two Pointers"), List.of("https://example.test/editorial"));

        ProblemGuidance guidance = guidanceService.getGuidance(problem.getId()).orElseThrow();
        assertEquals("improved draft", guidance.getClarifyText());
        assertEquals("now with an observation", guidance.getObservationText());
        assertEquals(GuidanceSource.LEARNER, guidance.getSource());
        assertEquals(List.of("Two Pointers"), guidanceService.getPrerequisites(problem.getId()));
        assertEquals(List.of("https://example.test/editorial"), guidanceService.getReferenceLinks(problem.getId()));
    }

    @Test
    void learnerTextEditsPreserveExistingPrerequisitesAndReferenceLinks() {
        Problem problem = fixtureProblem("TF-162-PRESERVE-METADATA");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT,
                "base clarify", "base observation", null, null, null, null, null,
                List.of("Arrays", "Two Pointers"), List.of("https://example.test/reference"));

        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER,
                "learner clarify", "base observation", null, null, null, null, null,
                null, null);

        assertEquals(List.of("Arrays", "Two Pointers"), guidanceService.getPrerequisites(problem.getId()));
        assertEquals(List.of("https://example.test/reference"), guidanceService.getReferenceLinks(problem.getId()));
    }

    @Test
    void learnerOverrideKeepsOriginalCodefitGuidanceAndProvenance() {
        Problem problem = fixtureProblem("TF-162-OVERRIDE-CODEFIT");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT,
                "CodeFit clarify", null, null, null, null, null, null, null, null);

        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER,
                "Learner clarify", null, null, null, null, null, null, null, null);

        ProblemGuidance active = guidanceService.getGuidance(problem.getId()).orElseThrow();
        ProblemGuidance original = guidanceService.getGuidance(problem.getId(), GuidanceSource.CODEFIT).orElseThrow();
        assertEquals(GuidanceSource.LEARNER, active.getSource());
        assertEquals("Learner clarify", active.getClarifyText());
        assertEquals(GuidanceSource.CODEFIT, original.getSource());
        assertEquals("CodeFit clarify", original.getClarifyText());
    }

    @Test
    void learnerOverrideKeepsOriginalImportedGuidanceAndProvenance() {
        Problem problem = fixtureProblem("TF-162-OVERRIDE-IMPORTED");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.IMPORTED,
                "Imported clarify", null, null, null, null, null, null, null, null);

        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER,
                "Learner clarify", null, null, null, null, null, null, null, null);

        ProblemGuidance original = guidanceService.getGuidance(problem.getId(), GuidanceSource.IMPORTED).orElseThrow();
        assertEquals(GuidanceSource.IMPORTED, original.getSource());
        assertEquals("Imported clarify", original.getClarifyText());
        assertEquals("Learner clarify", guidanceService.getGuidance(problem.getId()).orElseThrow().getClarifyText());
    }

    @Test
    void guidanceProvenanceIsPreservedAndDistinguishable() {
        Problem codefitAuthored = fixtureProblem("TF-162-PROV-CODEFIT");
        Problem learnerAuthored = fixtureProblem("TF-162-PROV-LEARNER");

        guidanceService.saveGuidance(codefitAuthored.getId(), GuidanceSource.CODEFIT, "clarify", null, null, null, null, null, null, null, null);
        guidanceService.saveGuidance(learnerAuthored.getId(), GuidanceSource.LEARNER, "clarify", null, null, null, null, null, null, null, null);

        assertEquals(GuidanceSource.CODEFIT, guidanceService.getGuidance(codefitAuthored.getId()).orElseThrow().getSource());
        assertEquals(GuidanceSource.LEARNER, guidanceService.getGuidance(learnerAuthored.getId()).orElseThrow().getSource());
    }

    @Test
    void computeAssistanceLevelMapsHintDepthToTheRightSolvedWithValue() {
        assertEquals(SolvedWith.SELF, guidanceService.computeAssistanceLevel(null));
        assertEquals(SolvedWith.HINT, guidanceService.computeAssistanceLevel(HintLevel.CLARIFY));
        assertEquals(SolvedWith.HINT, guidanceService.computeAssistanceLevel(HintLevel.OBSERVATION));
        assertEquals(SolvedWith.HINT, guidanceService.computeAssistanceLevel(HintLevel.APPROACH));
        assertEquals(SolvedWith.EDITORIAL, guidanceService.computeAssistanceLevel(HintLevel.EXPLANATION));
    }

    @Test
    void hintLevelNextStopsAtExplanation() {
        assertEquals(HintLevel.OBSERVATION, HintLevel.CLARIFY.next());
        assertEquals(HintLevel.APPROACH, HintLevel.OBSERVATION.next());
        assertEquals(HintLevel.EXPLANATION, HintLevel.APPROACH.next());
        assertNull(HintLevel.EXPLANATION.next());
    }

    @Test
    void theFullExplanationComposesIdeaPseudocodeComplexityAndCommonMistakes() {
        Problem problem = fixtureProblem("TF-162-EXPLANATION-PARTS");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT, null, null, null,
                "Shrink the window from both ends.", "lo = 0; hi = n - 1; while lo < hi: ...",
                "O(n) time, O(1) space.", "Off-by-one at the boundary.", null, null);

        ProblemGuidanceService.HintReveal reveal = guidanceService.revealLevel(problem.getId(), HintLevel.EXPLANATION);

        assertTrue(reveal.hasContent());
        assertTrue(reveal.text().contains("Shrink the window from both ends."));
        assertTrue(reveal.text().contains("lo = 0; hi = n - 1; while lo < hi: ..."));
        assertTrue(reveal.text().contains("O(n) time, O(1) space."));
        assertTrue(reveal.text().contains("Off-by-one at the boundary."));
    }

    @Test
    void anExplanationPartThatIsStillUnauthoredSaysSoRatherThanBeingSilentlyOmitted() {
        Problem problem = fixtureProblem("TF-162-EXPLANATION-PARTIAL");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT, null, null, null,
                "Shrink the window from both ends.", null, null, null, null, null);

        ProblemGuidance guidance = guidanceService.getGuidance(problem.getId()).orElseThrow();
        ProblemGuidanceService.HintReveal reveal = guidanceService.revealLevel(problem.getId(), HintLevel.EXPLANATION);

        assertFalse(guidance.hasCompleteExplanation(), "three of the four parts are still blank");
        assertTrue(reveal.text().contains("(not yet authored)"));
    }

    @Test
    void aFullyAuthoredExplanationReportsComplete() {
        Problem problem = fixtureProblem("TF-162-EXPLANATION-COMPLETE");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT, null, null, null,
                "idea", "pseudocode", "complexity", "mistakes", null, null);

        ProblemGuidance guidance = guidanceService.getGuidance(problem.getId()).orElseThrow();

        assertTrue(guidance.hasCompleteExplanation());
    }

    @Test
    void revealLevelShowsASpecificLevelWithoutChangingWhatsRecordedAsOpened() {
        Problem problem = fixtureProblem("TF-162-REVEAL-DIRECT");
        sessionService.reset(problem.getId());
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT, "clarify text", null, null, null, null, null, null, null, null);

        ProblemGuidanceService.HintReveal reveal = guidanceService.revealLevel(problem.getId(), HintLevel.CLARIFY);
        assertEquals("clarify text", reveal.text());
        assertTrue(guidanceService.getOpenedLevel(problem.getId()).isEmpty(), "revealLevel must not itself advance the opened level");
    }
}
