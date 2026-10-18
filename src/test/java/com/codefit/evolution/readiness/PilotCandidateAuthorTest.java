package com.codefit.evolution.readiness;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evaluation.EvaluationTypes.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.*;

/** Author validation, not participant data or an alternative evaluator. No DB writes/evidence. */
class PilotCandidateAuthorTest {
    private final BundledAssets assets = new BundledAssets();
    private final ChallengeSpec spec = PilotAssessmentForms.baselineCapacity();
    private final JavaMechanismVerifier verifier = new JavaMechanismVerifier();
    private final JavaCodeRunner runner = new JavaCodeRunner();

    private void runAuthorCases(String source, boolean shouldPass) {
        String harness = assets.read(spec.asset("harness"));
        try (var compiled = runner.compile(source + "\n" + harness, "Main")) {
            assertTrue(compiled.success(), compiled.rawOutput());
            for (var check : spec.rubric().checks()) {
                // Mutants are rejected by deterministic assertions, never probabilistic scheduling.
                if (!shouldPass && check.kind() != CheckKind.DETERMINISTIC_IO) continue;
                for (var sample : check.cases()) {
                    var result =
                            runner.run(
                                    compiled,
                                    sample.stdin(),
                                    new RunLimits(check.timeoutSeconds(), 128, 32768),
                                    new RunCancellationToken());
                    assertFalse(result.timedOut());
                    assertFalse(result.cancelled());
                    if (shouldPass) {
                        assertEquals(sample.expectedExit(), result.exitCode(), result.stderr());
                        assertEquals(sample.expectedStdout(), result.stdout());
                    } else {
                        assertNotEquals(sample.expectedExit(), result.exitCode());
                        assertTrue(result.stderr().contains("AssertionError"), result.stderr());
                    }
                }
            }
        }
    }

    @RepeatedTest(3)
    void referencePassesTheFiniteHarnessAndExistingScalarMonitorInspection() {
        String reference = assets.read(spec.asset("reference"));
        var inspection = verifier.inspect(reference, spec.familyId());
        assertTrue(inspection.admitted());
        assertEquals(CheckStatus.PASSED, inspection.boundary());
        runAuthorCases(reference, true);
    }

    @Test
    void starterReallyContainsTheAdvertisedSafetyAndAtomicityBugs() {
        String starter = assets.read(spec.asset("starter"));
        String harness = assets.read(spec.asset("harness"));
        assertTrue(starter.endsWith(harness));
        String fragment = starter.substring(0, starter.length() - harness.length());
        assertEquals(CheckStatus.FAILED, verifier.inspect(fragment, spec.familyId()).boundary());
        runAuthorCases(fragment, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mutant", "duplicate-mutant", "rejection-mutant", "constant-mutant"})
    void safetyMutantsFailDeterministicRequiredCases(String id) {
        var source = assets.read(spec.asset(id));
        var inspection = verifier.inspect(source, spec.familyId());
        assertTrue(
                inspection.admitted()); // Structural admission does not substitute for invariants.
        runAuthorCases(source, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-update-mutant", "volatile-mutant", "split-lock-mutant"})
    void invalidOperationBoundariesNeverGetVerifiedAtomicity(String id) {
        var inspection = verifier.inspect(assets.read(spec.asset(id)), spec.familyId());
        assertNotEquals(CheckStatus.PASSED, inspection.boundary());
        if (!id.equals("split-lock-mutant"))
            assertEquals(CheckStatus.FAILED, inspection.boundary());
    }

    @Test
    void validButUnsupportedSolutionRemainsUnsupportedRatherThanAClaimOfFailure() {
        String source = assets.read(spec.asset("unsupported-reference"));
        runAuthorCases(source, true);
        var inspection = verifier.inspect(source, spec.familyId());
        assertFalse(inspection.admitted());
        assertEquals(CheckStatus.UNSUPPORTED, inspection.boundary());
    }

    @Test
    void installedEvaluatorRejectsUnpublishedCandidateBeforeTouchingRunner() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        var prior =
                new ChallengePresentation.ExposureContext(
                        0, 0, PresentationKind.FIRST_PRESENTATION, 0, false, false);
        var quest =
                new EvolutionQuest(
                        new CatalogRef("readiness-baseline-capacity", 1),
                        spec.domainId(),
                        spec.graph(),
                        Cohort.BASELINE,
                        "Unpublished author form",
                        "Author validation only",
                        "No participant assigned",
                        spec.targets(),
                        spec.prerequisites(),
                        3,
                        40,
                        List.of(spec.ref()),
                        new EvolutionQuest.Completion(1, false, "No pilot authorization"),
                        null);
        var presentation =
                new ChallengePresentation(
                        "author-only",
                        quest.ref(),
                        spec.ref(),
                        spec.familyId(),
                        spec.variantId(),
                        spec.cohort(),
                        now,
                        Familiarity.UNKNOWN,
                        prior);
        String reference = assets.read(spec.asset("reference"));
        var request =
                new SubmissionRequest(
                        "author-only-command",
                        presentation.id(),
                        reference,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var snapshot =
                new SubmissionSnapshot(
                        request,
                        presentation,
                        CatalogCodec.encode(quest),
                        CatalogCodec.encode(spec),
                        1L,
                        null,
                        prior,
                        0,
                        0,
                        now,
                        JavaChallengeEvaluator.VERSION,
                        RunnerProfile.defaults(),
                        JavaChallengeEvaluator.assemble(spec, reference, assets),
                        null,
                        null,
                        null,
                        null,
                        null);
        var result =
                new JavaChallengeEvaluator(neverRun(), assets, Clock.fixed(now, ZoneOffset.UTC))
                        .evaluate(snapshot, new RunCancellationToken());
        assertEquals(Outcome.UNSUPPORTED, result.outcome());
        assertTrue(result.checks().stream().noneMatch(c -> c.status() == CheckStatus.PASSED));
    }

    static CodeRunner neverRun() {
        return new CodeRunner() {
            public boolean isAvailable() {
                throw new AssertionError("Unauthorized runner discovery");
            }

            public String getUnavailabilityReason() {
                throw new AssertionError("Unauthorized runner discovery");
            }

            public CompileOutcome compile(String source, String main) {
                throw new AssertionError("Unauthorized compile");
            }

            public RunResult run(
                    CompileOutcome compiled,
                    String input,
                    RunLimits limits,
                    RunCancellationToken cancellation) {
                throw new AssertionError("Unauthorized run");
            }
        };
    }
}
