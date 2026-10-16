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
}
