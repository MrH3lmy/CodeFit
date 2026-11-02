package com.codefit.evolution.adaptation;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.QuestTypes.AssetKind;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.time.Instant;

/** Investigation-only bounded observations; no private inputs or reports are emitted. */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class AdaptationContentionProbeTest {
    @TempDir Path directory;

    @Test
    void fiveConcurrentBaselines() throws Exception {
        environment("begin-concurrent");
        for (int trial = 0; trial < 5; trial++) {
            var f = new AdaptiveQuestIntegrationTest();
            f.directory = directory;
            f.freshDb();
            long start = System.nanoTime();
            String outcome = "SUCCESS";
            int pending =
                    f.verifyConcurrentResponseContract(
                            (actor, queue, elapsed, cpu) ->
                                    System.out.println(
                                            "ADAPTATION_PROBE kind=response actor="
                                                    + actor
                                                    + " queueMs="
                                                    + queue
                                                    + " elapsedMs="
                                                    + elapsed
                                                    + " threadCpuMs="
                                                    + cpu
                                                    + " finish="
                                                    + Instant.now()));
            outcome = "CONTRACT_PASSED pendingResponses=" + pending;
            long choices = f.count("evolution_adaptation_choices");
            assertTrue(choices <= 1);
            assertEquals(0, f.count("evolution_challenge_presentations"));
            assertEquals(0, f.count("evolution_evidence_observations"));
            System.out.println(
                    "ADAPTATION_PROBE kind=concurrent trial="
                            + trial
                            + " elapsedMs="
                            + (System.nanoTime() - start) / 1000000
                            + " outcome="
                            + outcome
                            + " choices="
                            + choices);
        }
        environment("end-concurrent");
    }

    @Test
    void twoRetentionBaselines() throws Exception {
        environment("begin-retention");
        for (int trial = 0; trial < 2; trial++) {
            var f =
                    new AdaptiveQuestIntegrationTest() {
                        @Override
                        SubmissionRequest pass(String id, int day) {
                            var request = request(id, day, AssetKind.REFERENCE);
                            long begin = System.nanoTime();
                            var result =
                                    EvaluationTestFixtures.service(clock(day), new MeasuredRunner())
                                            .submit(request);
                            System.out.println(
                                    "ADAPTATION_PROBE kind=submission finish="
                                            + Instant.now()
                                            + " elapsedMs="
                                            + (System.nanoTime() - begin) / 1000000
                                            + " outcome="
                                            + result.outcome());
                            assertFalse(
                                    JavaExecutionCoordinator.hasActiveOperation(),
                                    "evaluation gate must release on completion, including"
                                        + " timeout");
                            assertEquals(EvaluationTypes.Outcome.PASSED, result.outcome());
                            return request;
                        }
                    };
            f.directory = directory;
            f.freshDb();
            long start = System.nanoTime();
            f.realRehearsalDefersRetentionAndAllHeldOutContentRemainsUnpresented();
            System.out.println(
                    "ADAPTATION_PROBE kind=retention trial="
                            + trial
                            + " elapsedMs="
                            + (System.nanoTime() - start) / 1000000
                            + " outcome=SUCCESS");
        }
        environment("end-retention");
    }

    private static void environment(String phase) {
        String quota = "UNAVAILABLE", throttled = "UNAVAILABLE";
        try {
            quota =
                    java.nio.file.Files.readString(Path.of("/sys/fs/cgroup/cpu.max"))
                            .strip()
                            .replaceAll("\\s+", "/");
            throttled =
                    java.nio.file.Files.readAllLines(Path.of("/sys/fs/cgroup/cpu.stat")).stream()
                            .filter(line -> line.startsWith("throttled_usec "))
                            .findFirst()
                            .orElse("UNAVAILABLE")
                            .replace(' ', '=');
        } catch (java.io.IOException unavailable) {
            /* Portable: diagnostics unavailable, assertions still run. */
        }
        long nativeProcesses =
                ProcessHandle.allProcesses()
                        .filter(
                                p ->
                                        p.info()
                                                .command()
                                                .map(
                                                        c ->
                                                                c.endsWith("/java")
                                                                        || c.endsWith("/javac"))
                                                .orElse(false))
                        .count();
        System.out.println(
                "ADAPTATION_PROBE phase="
                        + phase
                        + " at="
                        + Instant.now()
                        + " cpuQuota="
                        + quota
                        + " "
                        + throttled
                        + " visibleNativeProcesses="
                        + nativeProcesses);
    }

    static final class MeasuredRunner implements CodeRunner {
        private static final java.util.concurrent.atomic.AtomicInteger EVENTS =
                new java.util.concurrent.atomic.AtomicInteger();
        private final JavaCodeRunner actual = new JavaCodeRunner();

        public boolean isAvailable() {
            return actual.isAvailable();
        }

        public String getUnavailabilityReason() {
            return actual.getUnavailabilityReason();
        }

        public CompileOutcome compile(String source, String main) {
            return actual.compile(source, main);
        }

        public CompileOutcome compile(
                String source, String main, RunLimits limits, RunCancellationToken token) {
            long begin = System.nanoTime();
            var outcome = actual.compile(source, main, limits, token);
            metric("compile", outcome.status().name(), begin, limits);
            return outcome;
        }

        public RunResult run(
                CompileOutcome compiled,
                String input,
                RunLimits limits,
                RunCancellationToken token) {
            long begin = System.nanoTime();
            var outcome = actual.run(compiled, input, limits, token);
            metric(
                    "run",
                    outcome.timedOut()
                            ? "TIMED_OUT"
                            : outcome.cancelled()
                                    ? "CANCELLED"
                                    : outcome.exitCode() != null && outcome.exitCode() == 0
                                            ? "EXITED_ZERO"
                                            : "RUNTIME_ERROR",
                    begin,
                    limits);
            return outcome;
        }

        private void metric(String phase, String status, long begin, RunLimits limits) {
            long live = ProcessHandle.current().children().filter(ProcessHandle::isAlive).count();
            if (EVENTS.incrementAndGet() <= 128)
                System.out.println(
                        "ADAPTATION_PROBE phase="
                                + phase
                                + " finish="
                                + Instant.now()
                                + " elapsedMs="
                                + (System.nanoTime() - begin) / 1000000
                                + " budgetMs="
                                + limits.timeoutSeconds() * 1000L
                                + " status="
                                + status
                                + " liveChildrenAfter="
                                + live
                                + " executorQueue=NONE");
            assertEquals(0, live, "runner must reap its child before returning");
        }
    }
}
