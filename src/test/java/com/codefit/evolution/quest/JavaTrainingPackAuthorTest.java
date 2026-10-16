package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.service.*;

import org.junit.jupiter.api.*;

import java.util.stream.Stream;

/** Author smoke validation only. No durable evaluation, learner grading or evidence generation. */
class JavaTrainingPackAuthorTest {
    @TestFactory
    Stream<DynamicTest> referencesAndKnownMutants() {
        return JavaTrainingPack.challenges().stream()
                .map(c -> DynamicTest.dynamicTest(c.ref().id(), () -> validateAuthorFixture(c)));
    }

    private void validateAuthorFixture(ChallengeSpec challenge) {
        var assets = new BundledAssets();
        String reference = assets.read(challenge.asset("reference"));
        String mutant = assets.read(challenge.asset("mutant"));
        assertNotEquals(reference, mutant);
        if (challenge.runner() == Runner.KEYED_RESPONSE) {
            assertEquals(assets.read(challenge.asset("answer-key")), reference);
            return;
        }
        String harness = assets.read(challenge.asset("harness"));
        // A future coordinator can reject an edited Main suffix and assemble the trusted harness.
        assertTrue(assets.read(challenge.asset("starter")).endsWith(harness));
        var runner = new JavaCodeRunner();
        assertReference(runner, reference + "\n" + harness, challenge);
        if (challenge.assets().stream().anyMatch(a -> a.id().equals("atomic-reference")))
            assertReference(
                    runner,
                    assets.read(challenge.asset("atomic-reference")) + "\n" + harness,
                    challenge);
        var functional =
                challenge.rubric().checks().stream()
                        .filter(c -> c.kind() == CheckKind.DETERMINISTIC_IO)
                        .findFirst()
                        .orElseThrow();
        try (var bad = runner.compile(mutant + "\n" + harness, "Main")) {
            assertTrue(bad.success(), bad.rawOutput());
            for (var sample : functional.cases()) {
                var result =
                        runner.run(
                                bad,
                                sample.stdin(),
                                new RunLimits(functional.timeoutSeconds(), 128, 32768),
                                null);
                assertFalse(result.timedOut());
                assertFalse(result.cancelled());
                assertNotEquals(
                        sample.expectedExit(),
                        result.exitCode(),
                        "Known functional mutant must fail deterministic cases");
            }
        }
    }

    private void assertReference(CodeRunner runner, String source, ChallengeSpec challenge) {
        try (var compiled = runner.compile(source, "Main")) {
            assertTrue(compiled.success(), compiled.rawOutput());
            for (var check : challenge.rubric().checks())
                for (var sample : check.cases()) {
                    var result =
                            runner.run(
                                    compiled,
                                    sample.stdin(),
                                    new RunLimits(check.timeoutSeconds(), 128, 32768),
                                    null);
                    assertFalse(result.timedOut());
                    assertFalse(result.cancelled());
                    assertEquals(sample.expectedExit(), result.exitCode(), result.stderr());
                    assertEquals(sample.expectedStdout(), result.stdout());
                }
        }
    }
}
