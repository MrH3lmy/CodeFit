package com.codefit.evolution.readiness;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;

class HeldOutPracticeBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"post-ledger", "post-expiry", "inventory-transfer", "queue-retention"})
    void directPracticeCannotDiscoverCompilerOrExecuteHeldOutChecks(String id) {
        var spec =
                JavaTrainingPack.challenges().stream()
                        .filter(s -> s.ref().id().equals(id))
                        .findFirst()
                        .orElseThrow();
        var evaluator =
                new JavaChallengeEvaluator(
                        PilotCandidateAuthorTest.neverRun(),
                        new BundledAssets(),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var rejection =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                evaluator.practice(
                                        spec,
                                        "private learner source",
                                        new RunCancellationToken()));
        assertTrue(rejection.getMessage().startsWith("Practice execution is only available"));
    }

    @Test
    void candidateCannotUsePracticeJustBecauseItHasABaselineLabel() {
        var evaluator =
                new JavaChallengeEvaluator(
                        PilotCandidateAuthorTest.neverRun(),
                        new BundledAssets(),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThrows(
                IllegalStateException.class,
                () ->
                        evaluator.practice(
                                PilotAssessmentForms.baselineCapacity(),
                                "private learner source",
                                new RunCancellationToken()));
    }

    @Test
    void installedBaselineAndPracticeKeepTheirExistingPublicPracticeRoute() {
        // No actual compiler here: reaching the explicit unavailable-JDK result proves role
        // admission.
        CodeRunner absent =
                new CodeRunner() {
                    public boolean isAvailable() {
                        return false;
                    }

                    public String getUnavailabilityReason() {
                        return "Unavailable author fixture";
                    }

                    public CompileOutcome compile(String source, String main) {
                        throw new AssertionError();
                    }

                    public RunResult run(
                            CompileOutcome compiled,
                            String input,
                            RunLimits limits,
                            RunCancellationToken token) {
                        throw new AssertionError();
                    }
                };
        var evaluator =
                new JavaChallengeEvaluator(
                        absent, new BundledAssets(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (String id : new String[] {"baseline-counter", "counter-repair"}) {
            var spec =
                    JavaTrainingPack.challenges().stream()
                            .filter(s -> s.ref().id().equals(id))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            evaluator.practice(
                                                    spec, "own source", new RunCancellationToken()))
                            .getMessage()
                            .contains("compiler/runner unavailable"));
        }
    }
}
