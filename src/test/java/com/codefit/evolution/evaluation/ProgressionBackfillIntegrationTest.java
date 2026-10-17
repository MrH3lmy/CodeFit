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
class ProgressionBackfillIntegrationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void preUpgradeInterruptedSubmissionResumesWithoutMintingAnEffortReward() throws Exception {
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
        coordinator.prepare(request);

        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE user_progress SET xp=432,level=5,streak_days=7 WHERE id=1");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_milestone_baseline");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_processed");
            s.execute("DELETE FROM schema_migrations WHERE version=24");
            s.execute("DELETE FROM schema_migrations WHERE version=23");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_scan");
            s.execute("DELETE FROM schema_migrations WHERE version=21");
            s.execute("DROP TRIGGER evolution_reward_state_immutable_update");
            s.execute("DROP TRIGGER evolution_reward_state_immutable_delete");
            s.execute("DROP TRIGGER evolution_reward_entitlements_immutable_update");
            s.execute("DROP TRIGGER evolution_reward_entitlements_immutable_delete");
            s.execute("DROP TABLE evolution_reward_entitlements");
            s.execute("DROP TABLE evolution_reward_state");
            s.execute("DROP TABLE evolution_reward_preferences");
            s.execute("DELETE FROM schema_migrations WHERE version IN(20,22)");
        }
        DatabaseConfig.initialize();

        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT xp,level,streak_days FROM user_progress WHERE id=1")) {
            assertTrue(r.next());
            assertEquals(432, r.getInt(1));
            assertEquals(5, r.getInt(2));
            assertEquals(7, r.getInt(3));
        }
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM evolution_reward_entitlements")) {
            assertTrue(r.next());
            assertEquals(0, r.getInt(1));
        }
    }
}
