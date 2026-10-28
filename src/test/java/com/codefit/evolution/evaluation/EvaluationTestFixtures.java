package com.codefit.evolution.evaluation;

import com.codefit.evolution.quest.BundledAssets;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.JavaCodeRunner;

import java.time.Clock;

/**
 * Trusted-clock test factory; production callers cannot backdate a command with an Instant
 * argument.
 */
public final class EvaluationTestFixtures {
    private EvaluationTestFixtures() {}

    public static ChallengeEvaluationCoordinator service(Clock clock) {
        var assets = new BundledAssets();
        return new ChallengeEvaluationCoordinator(
                new QuestCatalogRepository(),
                assets,
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, clock),
                clock,
                b -> {});
    }

    /** A missing installed JDK must remain a tool outcome during cold-start UI recovery. */
    public static ChallengeEvaluationCoordinator unavailableRunner(Clock clock) {
        var assets = new BundledAssets();
        var runner =
                new com.codefit.service.CodeRunner() {
                    public boolean isAvailable() {
                        return false;
                    }

                    public String getUnavailabilityReason() {
                        return "Local JDK unavailable";
                    }

                    public com.codefit.service.CompileOutcome compile(String source, String main) {
                        throw new AssertionError("Unavailable runner must not compile");
                    }

                    public com.codefit.service.RunResult run(
                            com.codefit.service.CompileOutcome compiled,
                            String input,
                            com.codefit.service.RunLimits limits,
                            com.codefit.service.RunCancellationToken cancellation) {
                        throw new AssertionError("Unavailable runner must not execute");
                    }
                };
        return new ChallengeEvaluationCoordinator(
                new QuestCatalogRepository(),
                assets,
                new JavaChallengeEvaluator(runner, assets, clock),
                clock,
                b -> {});
    }

    /** Leave real staged check results durable, before the canonical finalization transaction. */
    public static ChallengeEvaluationCoordinator interruptAfterStaging(Clock clock) {
        var assets = new BundledAssets();
        return new ChallengeEvaluationCoordinator(
                new QuestCatalogRepository(),
                assets,
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, clock),
                clock,
                b -> {
                    if (b == ChallengeEvaluationCoordinator.Boundary.STAGED)
                        throw new IllegalStateException("Simulated interruption after staging");
                });
    }

    /** UI lost-response fixture: the durable commit succeeds, then the first response is lost. */
    public static ChallengeEvaluationCoordinator loseFirstResponse(Clock clock) {
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        var assets = new BundledAssets();
        return new ChallengeEvaluationCoordinator(
                new QuestCatalogRepository(),
                assets,
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, clock),
                clock,
                boundary -> {
                    if (boundary == ChallengeEvaluationCoordinator.Boundary.AFTER_COMMIT
                            && lost.compareAndSet(false, true))
                        throw new IllegalStateException(
                                "Simulated response loss after durable commit");
                });
    }

    /** Coordinate cancellation at a durable boundary rather than racing JVM compilation speed. */
    public static ChallengeEvaluationCoordinator pauseAfterPrepared(
            Clock clock,
            java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release) {
        var assets = new BundledAssets();
        return new ChallengeEvaluationCoordinator(
                new QuestCatalogRepository(),
                assets,
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, clock),
                clock,
                boundary -> {
                    if (boundary == ChallengeEvaluationCoordinator.Boundary.PREPARED) {
                        entered.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("Test boundary not released");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                });
    }

    /**
     * Projection fixture: persist a bounded report through the real finalizer, not an engine claim.
     */
    public static void finalizeProjectionFixture(String command, EvaluationTypes.Outcome outcome) {
        var repository = new DurableEvaluationRepository();
        new QuestCatalogRepository()
                .transaction(
                        c -> {
                            var s = repository.find(c, command).orElseThrow();
                            var checks = new java.util.ArrayList<CheckResult>();
                            int index = 0;
                            for (var check : s.snapshot().challenge().rubric().checks()) {
                                var status =
                                        check.kind()
                                                        == com.codefit.evolution.quest.QuestTypes
                                                                .CheckKind.REFLECTION
                                                ? EvaluationTypes.CheckStatus.UNGRADED
                                                : switch (outcome) {
                                                    case PASSED ->
                                                            EvaluationTypes.CheckStatus.PASSED;
                                                    case FAILED ->
                                                            EvaluationTypes.CheckStatus.FAILED;
                                                    case PARTIAL ->
                                                            index++ == 0
                                                                    ? EvaluationTypes.CheckStatus
                                                                            .PASSED
                                                                    : EvaluationTypes.CheckStatus
                                                                            .FAILED;
                                                    case UNSUPPORTED ->
                                                            EvaluationTypes.CheckStatus.UNSUPPORTED;
                                                    case INCONCLUSIVE ->
                                                            EvaluationTypes.CheckStatus
                                                                    .INCONCLUSIVE;
                                                    case CANCELLED ->
                                                            EvaluationTypes.CheckStatus.CANCELLED;
                                                    default -> EvaluationTypes.CheckStatus.NOT_RUN;
                                                };
                                checks.add(
                                        new CheckResult(
                                                check.id(),
                                                status,
                                                "PRIVATE_DIAGNOSTIC_SENTINEL",
                                                java.util.List.of()));
                            }
                            var at = s.snapshot().submittedAt();
                            var report =
                                    new EvaluationResult(
                                            outcome,
                                            checks,
                                            "PRIVATE_REPORT_SENTINEL",
                                            at,
                                            at,
                                            s.snapshot().evaluatorVersion(),
                                            "test projection fixture");
                            var bytes = EvaluationCodec.encode(report);
                            String token = java.util.UUID.randomUUID().toString();
                            DurableEvaluationRepository.update(
                                    c,
                                    "INSERT INTO"
                                        + " evolution_evaluation_runs(token,submission_id,execution_number,payload,digest)"
                                        + " VALUES(?,?,?,?,?)",
                                    token,
                                    s.id(),
                                    1,
                                    bytes,
                                    com.codefit.evolution.quest.CatalogCodec.digest(bytes));
                            DurableEvaluationRepository.update(
                                    c,
                                    "UPDATE evolution_submissions SET"
                                        + " state='EVALUATED',staged_run=?,execution_number=1 WHERE"
                                        + " id=?",
                                    token,
                                    s.id());
                            return null;
                        });
        new ChallengeEvaluationCoordinator().resume(command);
    }
}
