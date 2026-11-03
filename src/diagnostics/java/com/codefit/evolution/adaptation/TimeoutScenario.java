package com.codefit.evolution.adaptation;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.diagnostics.TimeoutEvents;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.QuestTypes.AssetKind;
import com.codefit.service.*;

import java.nio.file.*;
import java.util.Comparator;

/** Standalone isolated experiment, NOT an ordinary test retry or production application entry. */
public final class TimeoutScenario {
    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || !java.util.Set.of("retention", "runner-regression").contains(args[0]))
            throw new IllegalArgumentException();
        Path output = Path.of(args[1]);
        Path directory = Files.createTempDirectory("codefit-timeout-isolated-");
        int exit = 0;
        try {
            for (String name :
                    java.util.List.of(
                            "com.codefit.service.JavaCodeRunner",
                            "com.codefit.service.RunCancellationToken",
                            "com.codefit.evolution.evaluation.JavaChallengeEvaluator"))
                Class.forName(name);
            TimeoutEvents.requireInstrumentation();
            if (args[0].equals("runner-regression")) regression();
            else {
                var f =
                        new AdaptiveQuestIntegrationTest() {
                            int submission;

                            @Override
                            SubmissionRequest pass(String id, int day) {
                                var request = request(id, day, AssetKind.REFERENCE);
                                TimeoutEvents.submission("submission-" + (++submission), id + "@1");
                                var coordinator = EvaluationTestFixtures.service(clock(day));
                                var view = coordinator.submit(request);
                                var input =
                                        new DurableEvaluationRepository()
                                                .evaluationInput(request.id())
                                                .orElseThrow();
                                boolean replay = view.equals(coordinator.submit(request));
                                TimeoutEvents.finalized(
                                        view.outcome().name(),
                                        input.observation().outcome().name(),
                                        JavaExecutionCoordinator.hasActiveOperation(),
                                        replay);
                                assertEquals(EvaluationTypes.Stage.FINALIZED, view.stage());
                                assertFalse(JavaExecutionCoordinator.hasActiveOperation());
                                assertTrue(
                                        replay,
                                        "Finalized replay must return original result without"
                                                + " another runner phase");
                                if (view.outcome() == EvaluationTypes.Outcome.TIMED_OUT)
                                    assertEquals(
                                            com.codefit.evolution.evidence.EvidenceTypes.Outcome
                                                    .UNAVAILABLE,
                                            input.observation().outcome());
                                assertEquals(EvaluationTypes.Outcome.PASSED, view.outcome());
                                return request;
                            }
                        };
                f.directory = directory;
                f.freshDb();
                f.realRehearsalDefersRetentionAndAllHeldOutContentRemainsUnpresented();
            }
        } catch (Throwable failure) {
            exit = 1; // Exception messages/stack traces can contain private inputs: not exported.
        } finally {
            try {
                TimeoutEvents.dump(output, exit);
            } finally {
                try (var files = Files.walk(directory)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(p);
                }
            }
        }
        System.exit(exit);
    }

    private static void regression() throws Exception {
        var runner = new JavaCodeRunner();
        TimeoutEvents.submission("bounded-loop", "synthetic@1");
        try (var compiled =
                runner.compile(
                        "public class Loop {public static void main(String[] args){for(;;)"
                                + " Thread.onSpinWait();}}",
                        "Loop",
                        new RunLimits(15, 128, 1024),
                        new RunCancellationToken())) {
            assertTrue(compiled.success());
            TimeoutEvents.check("synthetic-loop");
            var result =
                    runner.run(
                            compiled, "", new RunLimits(1, 128, 1024), new RunCancellationToken());
            assertTrue(result.timedOut());
            TimeoutEvents.check("synthetic-cancel");
            var attached = new java.util.concurrent.CountDownLatch(1);
            var token = new RunCancellationToken(p -> attached.countDown());
            try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var cancellation =
                        worker.submit(
                                () -> {
                                    assertTrue(
                                            attached.await(
                                                    5, java.util.concurrent.TimeUnit.SECONDS));
                                    token.cancel();
                                    return true;
                                });
                var cancelled = runner.run(compiled, "", new RunLimits(5, 128, 1024), token);
                assertTrue(cancellation.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(cancelled.cancelled());
            }
        }
    }
}
