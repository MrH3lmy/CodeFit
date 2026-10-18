package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.mastery.MasteryTypes;
import com.codefit.evolution.player.PlayerSystemService;
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
        String token = UUID.randomUUID().toString();
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);
        var first = preparedRequest(coordinator, "counter-repair", token + "-one");
        var second = preparedRequest(coordinator, "remaining-capacity", token + "-two");

        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE user_progress SET xp=432,level=5,streak_days=7 WHERE id=1");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_milestone_baseline");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_processed");
            s.execute("DELETE FROM schema_migrations WHERE version IN(24,25)");
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
            s.execute("DELETE FROM schema_migrations WHERE version IN(20,22,25)");
        }
        DatabaseConfig.initialize();

        assertEquals(Outcome.PASSED, coordinator.submit(first).outcome());
        assertEquals(Outcome.PASSED, coordinator.submit(second).outcome());
        var overview = new PlayerSystemService(CLOCK).overview();
        assertEquals(
                MasteryTypes.Band.INDEPENDENT,
                overview.estimates().stream()
                        .filter(
                                r ->
                                        r.estimate()
                                                .skill()
                                                .skillId()
                                                .equals("java.concurrency.atomic-update"))
                        .findFirst()
                        .orElseThrow()
                        .estimate()
                        .band());
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT xp,level,streak_days FROM user_progress WHERE id=1")) {
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
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM evolution_reward_entitlements"
                                        + " WHERE reward_type IN ('INDEPENDENT_MILESTONE',"
                                        + "'RETENTION_MILESTONE')")) {
            assertTrue(r.next());
            assertEquals(0, r.getInt(1));
        }
    }

    private SubmissionRequest preparedRequest(
            ChallengeEvaluationCoordinator coordinator, String challengeId, String token) {
        var catalog = new QuestCatalogRepository();
        var assets = new BundledAssets();
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
        coordinator.prepare(request);
        return request;
    }
}
