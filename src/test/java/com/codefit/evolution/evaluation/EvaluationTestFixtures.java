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
}
