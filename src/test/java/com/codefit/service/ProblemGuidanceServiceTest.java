package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.ProblemSolvingSession;
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
                "Two-pointer scan from both ends.", "O(n) time, O(1) space; common mistake is off-by-one.",
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

        // Already at the top: calling again must not throw or move past EXPLANATION.
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

        // Finishing/resetting a session (e.g. a submission was finalized) starts the next attempt fresh.
        sessionService.reset(problem.getId());

        assertTrue(guidanceService.getOpenedLevel(problem.getId()).isEmpty(), "a fresh attempt has no hint opened yet");
    }

    @Test
    void openingALevelWithNoAuthoredTextReportsMissingContentRatherThanFabricatingIt() {
        Problem problem = fixtureProblem("TF-162-MISSING");
        sessionService.reset(problem.getId());
        // No guidance saved at all for this problem.

        ProblemGuidanceService.HintReveal reveal = guidanceService.openNextHintLevel(problem.getId());

        assertEquals(HintLevel.CLARIFY, reveal.level());
        assertFalse(reveal.hasContent());
        assertNull(reveal.text());
    }

    @Test
    void savingGuidanceTwiceEditsInPlaceRatherThanCreatingASecondRow() {
        Problem problem = fixtureProblem("TF-162-EDIT");
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER, "first draft", null, null, null, null, null);

        guidanceService.saveGuidance(problem.getId(), GuidanceSource.LEARNER, "improved draft", "now with an observation",
                null, null, List.of("Two Pointers"), List.of("https://example.test/editorial"));

        ProblemGuidance guidance = guidanceService.getGuidance(problem.getId()).orElseThrow();
        assertEquals("improved draft", guidance.getClarifyText());
        assertEquals("now with an observation", guidance.getObservationText());
        assertEquals(GuidanceSource.LEARNER, guidance.getSource());
        assertEquals(List.of("Two Pointers"), guidanceService.getPrerequisites(problem.getId()));
        assertEquals(List.of("https://example.test/editorial"), guidanceService.getReferenceLinks(problem.getId()));
    }

    @Test
    void guidanceProvenanceIsPreservedAndDistinguishable() {
        Problem codefitAuthored = fixtureProblem("TF-162-PROV-CODEFIT");
        Problem learnerAuthored = fixtureProblem("TF-162-PROV-LEARNER");

        guidanceService.saveGuidance(codefitAuthored.getId(), GuidanceSource.CODEFIT, "clarify", null, null, null, null, null);
        guidanceService.saveGuidance(learnerAuthored.getId(), GuidanceSource.LEARNER, "clarify", null, null, null, null, null);

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
    void revealLevelShowsASpecificLevelWithoutChangingWhatsRecordedAsOpened() {
        Problem problem = fixtureProblem("TF-162-REVEAL-DIRECT");
        sessionService.reset(problem.getId());
        guidanceService.saveGuidance(problem.getId(), GuidanceSource.CODEFIT, "clarify text", null, null, null, null, null);

        ProblemGuidanceService.HintReveal reveal = guidanceService.revealLevel(problem.getId(), HintLevel.CLARIFY);
        assertEquals("clarify text", reveal.text());
        assertTrue(guidanceService.getOpenedLevel(problem.getId()).isEmpty(), "revealLevel must not itself advance the opened level");
    }
}
