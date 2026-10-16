package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.player.EvolutionJournal;
import com.codefit.evolution.progression.ProgressionService;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.ChallengePresentationService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProgressionEvaluationIntegrationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    @BeforeEach
    void freshDatabase() {
        DatabaseConfig.useDatabaseFile(directory.resolve("progression.db"));
        DatabaseConfig.initialize();
    }

    @Test
    void finalizedEffortRewardReconcilesAndIsIdempotent() throws SQLException {
        var catalog = new QuestCatalogRepository();
        var assets = new BundledAssets();
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        String token = UUID.randomUUID().toString();
        String presentationId = "presentation-" + token;
        String commandId = "submission-" + token;
        new ChallengePresentationService(catalog, assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentationId,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String solution =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        commandId,
                        presentationId,
                        solution,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);

        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(0, xp());
        var progression = new ProgressionService();
        assertEquals(0, progression.reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
        assertTrue(
                new EvolutionJournal().page(null, 64).entries().stream()
                        .anyMatch(
                                entry ->
                                        entry.kind().equals("REWARD")
                                                && entry.message().contains(commandId)
                                                && entry.message().contains("policy v1")));
        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(10, xp());
        assertEquals(1, entitlements());

        var repeated =
                new SubmissionRequest(
                        "retry-" + token,
                        presentationId,
                        solution,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        assertEquals(Outcome.PASSED, coordinator.submit(repeated).outcome());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
    }

    @Test
    void optedOutCompletedObjectiveIsAuditedButNeverAwardedAfterReenable() throws Exception {
        var catalog = new QuestCatalogRepository();
        var assets = new BundledAssets();
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        String token = UUID.randomUUID().toString();
        String presentationId = "presentation-" + token;
        new ChallengePresentationService(catalog, assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentationId,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String solution =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "submission-" + token,
                        presentationId,
                        solution,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);
        var progression = new ProgressionService();
        progression.setRewardsEnabled(false);

        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(0, progression.reconcilePendingEffortRewards());
        assertEquals(0, xp());
        assertEquals("OPTED_OUT", progression.snapshot().history().getFirst().status());
        progression.setRewardsEnabled(true);
        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(0, xp());
        assertEquals(1, entitlements());
    }

    @Test
    void partialAssistedEvaluationEarnsBoundedEffortReward() throws Exception {
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        String source =
                new BundledAssets()
                        .read(
                                challenge.assets().stream()
                                        .filter(a -> a.kind() == AssetKind.REFERENCE)
                                        .findFirst()
                                        .orElseThrow())
                        .replace("total += delta;", "total = 0;");
        assertTrue(source.contains("total = 0;"));
        String presentation = "partial-" + UUID.randomUUID();
        new ChallengePresentationService(new QuestCatalogRepository(), new BundledAssets(), CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        var request =
                new SubmissionRequest(
                        "partial-" + UUID.randomUUID(),
                        presentation,
                        source,
                        OutsideAssistance.DECLARED,
                        "");
        var result = new ChallengeEvaluationCoordinator(CLOCK).submit(request);
        assertEquals(Outcome.PARTIAL, result.outcome(), result.checks().toString());
        assertEquals(0, xp());
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
    }

    @Test
    void correctionBeforeRewardRetryDoesNotCreateAnEffortEntitlement() throws Exception {
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        String presentation = "corrected-" + UUID.randomUUID();
        new ChallengePresentationService(new QuestCatalogRepository(), new BundledAssets(), CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String source =
                new BundledAssets()
                        .read(
                                challenge.assets().stream()
                                        .filter(a -> a.kind() == AssetKind.REFERENCE)
                                        .findFirst()
                                        .orElseThrow());
        var request =
                new SubmissionRequest(
                        "corrected-" + UUID.randomUUID(),
                        presentation,
                        source,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);
        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        coordinator.invalidate(request.id(), "before-reward", true);
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(0, xp());
        assertEquals(0, entitlements());
    }

    @Test
    void finalizedFailedEvaluationEarnsTheSameBoundedEffortReward() throws Exception {
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        String presentation = "failed-" + UUID.randomUUID();
        var catalog = new QuestCatalogRepository();
        var assets = new BundledAssets();
        new ChallengePresentationService(catalog, assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String source =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "failed-" + UUID.randomUUID(),
                        presentation,
                        source,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var evaluator =
                new JavaChallengeEvaluator(new com.codefit.service.JavaCodeRunner(), assets, CLOCK) {
                    @Override
                    public EvaluationResult evaluate(
                            SubmissionSnapshot snapshot,
                            com.codefit.service.RunCancellationToken token) {
                        var evaluated = super.evaluate(snapshot, token);
                        return new EvaluationResult(
                                Outcome.FAILED,
                                evaluated.checks(),
                                "Fixture confirmed a failed result",
                                evaluated.startedAt(),
                                evaluated.finishedAt(),
                                evaluated.evaluatorVersion(),
                                evaluated.runtime());
                    }
                };
        var coordinator =
                new ChallengeEvaluationCoordinator(catalog, assets, evaluator, CLOCK, b -> {});

        assertEquals(Outcome.FAILED, coordinator.submit(request).outcome());
        assertEquals(0, xp());
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
    }

    @Test
    void concurrentFinalizedRewardReconciliationDoesNotLoseXpUpdates() throws Exception {
        var first = submitReference("counter-repair");
        var second = submitReference("queue-retention");
        assertEquals(Outcome.PASSED, first.outcome());
        assertEquals(Outcome.PASSED, second.outcome());

        var service = new ProgressionService();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = workers.submit(service::reconcilePendingEffortRewards);
            var b = workers.submit(service::reconcilePendingEffortRewards);
            a.get(30, java.util.concurrent.TimeUnit.SECONDS);
            b.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0, service.reconcilePendingEffortRewards());
        assertEquals(20, xp());
        assertEquals(2, entitlements());
    }

    private ChallengeEvaluationCoordinator.View submitReference(String challengeId)
            throws SQLException {
        var challenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals(challengeId))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge.ref()))
                        .findFirst()
                        .orElseThrow();
        var assets = new BundledAssets();
        String presentation = "concurrent-" + UUID.randomUUID();
        new ChallengePresentationService(new QuestCatalogRepository(), assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String source =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        return new ChallengeEvaluationCoordinator(CLOCK)
                .submit(
                        new SubmissionRequest(
                                "concurrent-" + UUID.randomUUID(),
                                presentation,
                                source,
                                OutsideAssistance.NONE_DECLARED,
                                ""));
    }

    private int xp() throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT xp FROM user_progress WHERE id=1")) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private int entitlements() throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM evolution_reward_entitlements")) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }
}
