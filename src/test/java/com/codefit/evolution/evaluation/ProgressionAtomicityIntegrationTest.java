package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.ChallengePresentationService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.*;
import java.util.UUID;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProgressionAtomicityIntegrationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void balanceFailureLeavesFinalizationDurableAndRetryAwardsExactlyOnce() throws Exception {
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
        String artifact =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "submission-" + token,
                        presentationId,
                        artifact,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);
        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute(
                    "CREATE TRIGGER reject_progression_xp BEFORE UPDATE ON user_progress"
                            + " WHEN NEW.xp<>OLD.xp BEGIN SELECT RAISE(ABORT,'fixture'); END");
        }

        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(Stage.FINALIZED, coordinator.status(request.id()).stage());
        assertEquals(0, scalar("SELECT xp FROM user_progress WHERE id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM evolution_reward_entitlements"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
        assertEquals(
                1,
                new com.codefit.evolution.progression.ProgressionService()
                        .reconcilePendingEffortRewards());
        assertEquals(Stage.FINALIZED, coordinator.status(request.id()).stage());

        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute("DROP TRIGGER reject_progression_xp");
        }
        assertEquals(
                0,
                new com.codefit.evolution.progression.ProgressionService()
                        .reconcilePendingEffortRewards());
        assertEquals(Stage.FINALIZED, coordinator.resume(request.id()).stage());
        assertEquals(10, scalar("SELECT xp FROM user_progress WHERE id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM evolution_reward_entitlements"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
    }

    private long scalar(String sql) throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }
}
