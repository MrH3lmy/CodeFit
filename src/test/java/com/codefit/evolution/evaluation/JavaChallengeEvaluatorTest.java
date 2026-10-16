package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;
import com.codefit.service.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

class JavaChallengeEvaluatorTest {
    private final BundledAssets assets = new BundledAssets();
    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");
    private final JavaChallengeEvaluator evaluator =
            new JavaChallengeEvaluator(
                    new JavaCodeRunner(), assets, Clock.fixed(NOW, ZoneOffset.UTC));

    private ChallengeSpec spec(String id) {
        return JavaTrainingPack.challenges().stream()
                .filter(c -> c.ref().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private String source(ChallengeSpec s, AssetKind kind) {
        return assets.read(
                s.assets().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow());
    }

    private SubmissionSnapshot snapshot(ChallengeSpec spec, String artifact) {
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        var prior = new ExposureContext(0, 0, PresentationKind.FIRST_PRESENTATION, 0, false, false);
        var receipt =
                new ChallengePresentation(
                        "presentation",
                        quest.ref(),
                        spec.ref(),
                        spec.familyId(),
                        spec.variantId(),
                        spec.cohort(),
                        NOW,
                        Familiarity.UNKNOWN,
                        prior);
        return new SubmissionSnapshot(
                new SubmissionRequest(
                        "command", "presentation", artifact, OutsideAssistance.UNKNOWN, ""),
                receipt,
                CatalogCodec.encode(quest),
                CatalogCodec.encode(spec),
                spec.runner() == Runner.JAVA21_MAIN ? 1L : null,
                spec.runner() == Runner.KEYED_RESPONSE ? 1L : null,
                prior,
                0,
                0,
                NOW,
                JavaChallengeEvaluator.VERSION,
                RunnerProfile.defaults(),
                JavaChallengeEvaluator.assemble(spec, artifact, assets),
                null,
                null,
                null,
                null,
                null);
    }

    @TestFactory
    Stream<DynamicTest> allPackReferencesPassTheirActualChecks() {
        return JavaTrainingPack.challenges().stream()
                .map(
                        s ->
                                DynamicTest.dynamicTest(
                                        s.ref().tag(),
                                        () -> {
                                            var result =
                                                    evaluator.evaluate(
                                                            snapshot(
                                                                    s,
                                                                    source(s, AssetKind.REFERENCE)),
                                                            new RunCancellationToken());
                                            assertEquals(
                                                    Outcome.PASSED,
                                                    result.outcome(),
                                                    () -> result.toString());
                                            assertTrue(
                                                    result.checks().stream()
                                                            .filter(
                                                                    c ->
                                                                            s
                                                                                    .rubric()
                                                                                    .checks()
                                                                                    .stream()
                                                                                    .anyMatch(
                                                                                            d ->
                                                                                                    d.id().equals(
                                                                                                                            c
                                                                                                                                    .checkId())
                                                                                                            && d
                                                                                                                    .required()))
                                                            .allMatch(
                                                                    c ->
                                                                            c.status()
                                                                                    == CheckStatus
                                                                                            .PASSED));
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> allPackMutantsCannotProduceVerifiedCompletion() {
        return JavaTrainingPack.challenges().stream()
                .flatMap(
                        s ->
                                s.assets().stream()
                                        .filter(a -> a.kind() == AssetKind.MUTANT)
                                        .map(
                                                a ->
                                                        DynamicTest.dynamicTest(
                                                                s.ref().tag() + "/" + a.id(),
                                                                () -> {
                                                                    var result =
                                                                            evaluator.evaluate(
                                                                                    snapshot(
                                                                                            s,
                                                                                            assets
                                                                                                    .read(
                                                                                                            a)),
                                                                                    new RunCancellationToken());
                                                                    assertNotEquals(
                                                                            Outcome.PASSED,
                                                                            result.outcome(),
                                                                            () ->
                                                                                    result
                                                                                            .toString());
                                                                })));
    }

    @Test
    void atomicPrimitiveAlternativePasses() {
        var s = spec("counter-repair");
        var r =
                evaluator.evaluate(
                        snapshot(s, assets.read(s.asset("atomic-reference"))),
                        new RunCancellationToken());
        assertEquals(Outcome.PASSED, r.outcome(), r::toString);
    }

    @Test
    void volatileCompoundUpdateIsReliablyRejectedWithoutRelyingOnStress() {
        var s = spec("counter-repair");
        var inspection =
                new JavaMechanismVerifier()
                        .inspect(assets.read(s.asset("volatile-mutant")), s.familyId());
        assertTrue(inspection.admitted());
        assertEquals(CheckStatus.FAILED, inspection.boundary());
    }

    @Test
    void splitAtomicReadWriteCannotSatisfyRequiredBoundary() {
        var s = spec("counter-repair");
        String artifact =
                assets.read(s.asset("atomic-reference"))
                        .replace("total.addAndGet(delta)", "total.set(total.get() + delta)");
        var r = evaluator.evaluate(snapshot(s, artifact), new RunCancellationToken());
        assertEquals(Outcome.UNSUPPORTED, r.outcome());
    }

    @Test
    void extraOrModifiedMainCannotReplaceTrustedHarness() {
        var s = spec("counter-repair");
        String spoof =
                source(s, AssetKind.REFERENCE)
                        + "\n"
                        + "class Main { public static void main(String[] args) {"
                        + " System.out.println(\"OK\"); } }";
        assertNotEquals(
                Outcome.PASSED,
                evaluator.evaluate(snapshot(s, spoof), new RunCancellationToken()).outcome());
    }

    @Test
    void printingExpectedOutputOrExitingIsNotAnAdmittedMechanism() {
        var s = spec("counter-repair");
        String spoof =
                source(s, AssetKind.REFERENCE)
                        .replace("total += delta;", "System.out.println(\"OK\"); System.exit(0);");
        assertEquals(
                Outcome.UNSUPPORTED,
                evaluator.evaluate(snapshot(s, spoof), new RunCancellationToken()).outcome());
    }

    @Test
    void fullSavedDraftOnlyAcceptsExactTrustedHarnessSuffix() {
        var s = spec("counter-repair");
        String source = source(s, AssetKind.REFERENCE) + source(s, AssetKind.HARNESS);
        assertEquals(
                Outcome.PASSED,
                evaluator.evaluate(snapshot(s, source), new RunCancellationToken()).outcome());
    }

    @Test
    void cancellationBeforeCompileDoesNotRunAnyCheck() {
        var s = spec("counter-repair");
        var token = new RunCancellationToken();
        token.cancel();
        assertEquals(
                Outcome.CANCELLED,
                evaluator.evaluate(snapshot(s, source(s, AssetKind.REFERENCE)), token).outcome());
    }

    @Test
    void missingExactAssetsIsAnEvaluatorFailure() {
        var s = spec("counter-repair");
        var missing =
                new BundledAssets() {
                    @Override
                    public String read(AssetRef a) {
                        throw new IllegalStateException("missing");
                    }
                };
        var evaluator =
                new JavaChallengeEvaluator(
                        new JavaCodeRunner(), missing, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(
                Outcome.EVALUATOR_ERROR,
                evaluator
                        .evaluate(
                                snapshot(s, source(s, AssetKind.REFERENCE)),
                                new RunCancellationToken())
                        .outcome());
    }

    @Test
    void artifactAndResultCodecsAreCanonicalAndRejectTampering() {
        var s = spec("baseline-trace");
        var frozen = snapshot(s, source(s, AssetKind.REFERENCE));
        assertEquals(frozen, EvaluationCodec.submission(EvaluationCodec.encode(frozen)));
        var r = evaluator.evaluate(frozen, new RunCancellationToken());
        assertEquals(r, EvaluationCodec.result(EvaluationCodec.encode(r)));
        byte[] b =
                Arrays.copyOf(
                        EvaluationCodec.encode(frozen), EvaluationCodec.encode(frozen).length + 1);
        assertThrows(IllegalArgumentException.class, () -> EvaluationCodec.submission(b));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCodec.result(new byte[8]));
    }

    @Test
    void unsupportedCodeFormsAreFailClosed() {
        var verifier = new JavaMechanismVerifier();
        for (String fragment :
                List.of(
                        "class Exercise extends Thread {}",
                        "class Exercise { static { System.exit(0); } }",
                        "class Exercise { public synchronized Object leak(){return this;} }",
                        "class Exercise { private final Object m=new Object(); public int"
                                + " x(){synchronized(m){return 1;}} }",
                        "class Main {} class Exercise {}"))
            assertNotEquals(
                    CheckStatus.PASSED,
                    verifier.inspect(fragment, "metric-counter").boundary(),
                    fragment);
    }

    @Test
    void environmentalRunFailuresHaveDistinctUnavailableOutcomes() {
        var s = spec("counter-repair");
        var frozen = snapshot(s, source(s, AssetKind.REFERENCE));
        var fixtures = new LinkedHashMap<RunResult, Outcome>();
        fixtures.put(new RunResult(true, false, null, "", "", false, 10), Outcome.TIMED_OUT);
        fixtures.put(new RunResult(false, true, null, "", "", false, 10), Outcome.CANCELLED);
        fixtures.put(
                new RunResult(false, false, 1, "", "java.lang.OutOfMemoryError", false, 10),
                Outcome.RESOURCE_EXHAUSTED);
        fixtures.put(
                new RunResult(false, false, 1, "", "java.lang.NullPointerException", false, 10),
                Outcome.RUNTIME_ERROR);
        fixtures.put(
                new RunResult(false, false, null, "", "Could not start JVM", false, 10),
                Outcome.EVALUATOR_ERROR);
        fixtures.put(new RunResult(false, false, 0, "OK\n", "", true, 10), Outcome.INCONCLUSIVE);
        fixtures.put(
                new RunResult(
                        false, false, 1, "", "AssertionError: Observed nontermination", false, 10),
                Outcome.INCONCLUSIVE);
        for (var fixture : fixtures.entrySet()) {
            var real = new JavaCodeRunner();
            CodeRunner runner =
                    new CodeRunner() {
                        public boolean isAvailable() {
                            return true;
                        }

                        public String getUnavailabilityReason() {
                            return null;
                        }

                        public CompileOutcome compile(String code, String name) {
                            return real.compile(code, name);
                        }

                        public RunResult run(
                                CompileOutcome compiled,
                                String input,
                                RunLimits limits,
                                RunCancellationToken token) {
                            return fixture.getKey();
                        }
                    };
            var engine =
                    new JavaChallengeEvaluator(runner, assets, Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(
                    fixture.getValue(),
                    engine.evaluate(frozen, new RunCancellationToken()).outcome());
        }
    }

    @Test
    void compileErrorsAreNotWrongAnswerOrVerifiedCapabilityFailures() {
        var s = spec("counter-repair");
        assertEquals(
                Outcome.COMPILE_ERROR,
                evaluator
                        .evaluate(
                                snapshot(s, "class Exercise { invalid Java }"),
                                new RunCancellationToken())
                        .outcome());
    }

    @Test
    void unavailableRunnerDoesNotPretendToGrade() {
        var s = spec("counter-repair");
        CodeRunner unavailable =
                new CodeRunner() {
                    public boolean isAvailable() {
                        return false;
                    }

                    public String getUnavailabilityReason() {
                        return "JDK missing";
                    }

                    public CompileOutcome compile(String a, String b) {
                        throw new AssertionError("Must not compile");
                    }

                    public RunResult run(
                            CompileOutcome a, String b, RunLimits c, RunCancellationToken d) {
                        throw new AssertionError("Must not run");
                    }
                };
        assertEquals(
                Outcome.TOOL_UNAVAILABLE,
                new JavaChallengeEvaluator(unavailable, assets, Clock.fixed(NOW, ZoneOffset.UTC))
                        .evaluate(
                                snapshot(s, source(s, AssetKind.REFERENCE)),
                                new RunCancellationToken())
                        .outcome());
    }

    @Test
    void unsupportedEvaluatorVersionNeverRunsOrPasses() {
        var s = spec("baseline-trace");
        var f = snapshot(s, source(s, AssetKind.REFERENCE));
        var future =
                new SubmissionSnapshot(
                        f.request(),
                        f.presentation(),
                        f.questManifest(),
                        f.challengeManifest(),
                        f.problemId(),
                        f.assessmentItemId(),
                        f.exposureAtSubmission(),
                        0,
                        0,
                        NOW,
                        "future-v99",
                        f.profile(),
                        f.executableSource(),
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(
                Outcome.UNSUPPORTED,
                evaluator.evaluate(future, new RunCancellationToken()).outcome());
    }

    @Test
    void constantCounterOutputFailsActualFunctionalRequirement() {
        var s = spec("counter-repair");
        String broken = source(s, AssetKind.REFERENCE).replace("return total;", "return 100000;");
        var result = evaluator.evaluate(snapshot(s, broken), new RunCancellationToken());
        assertNotEquals(Outcome.PASSED, result.outcome());
        assertEquals(
                CheckStatus.FAILED,
                result.checks().stream()
                        .filter(c -> c.checkId().equals("functional"))
                        .findFirst()
                        .orElseThrow()
                        .status());
    }

    @Test
    void avoidingDeadlockByDroppingTransfersFailsConservationChecks() {
        var s = spec("lock-order-repair");
        String broken =
                "class Exercise { Exercise(int a,int b){} public synchronized boolean move(int"
                        + " from,int to,int n){return true;} public synchronized int balance(int"
                        + " i){return 100000;} }";
        var result = evaluator.evaluate(snapshot(s, broken), new RunCancellationToken());
        assertEquals(
                CheckStatus.FAILED,
                result.checks().stream()
                        .filter(c -> c.checkId().equals("functional"))
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertNotEquals(Outcome.PASSED, result.outcome());
    }

    @Test
    void emptyKeyedResponseIsInsufficientEvidence() {
        var s = spec("baseline-trace");
        assertEquals(
                Outcome.INCONCLUSIVE,
                evaluator.evaluate(snapshot(s, "  "), new RunCancellationToken()).outcome());
    }

    @Test
    void atomicArraysAndMutableReceiversCannotMasqueradeAsOneScalarAtomicOperation() {
        String source =
                "class Exercise { private final java.util.concurrent.atomic.AtomicLong[] cells={new"
                    + " java.util.concurrent.atomic.AtomicLong()}; public void add(long"
                    + " delta){cells[0].addAndGet(delta); if(delta==123456789)cells[0]=new"
                    + " java.util.concurrent.atomic.AtomicLong();} public long value(){return"
                    + " cells[0].get();} }";
        var inspection = new JavaMechanismVerifier().inspect(source, "metric-counter");
        assertNotEquals(CheckStatus.PASSED, inspection.boundary());
        var s = spec("counter-repair");
        assertNotEquals(
                Outcome.PASSED,
                evaluator.evaluate(snapshot(s, source), new RunCancellationToken()).outcome());
    }
}
