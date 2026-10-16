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
    void completedEffortRewardCommitsWithSubmissionAndIsIdempotent() throws SQLException {
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
        assertEquals(0, xp());
        assertEquals("OPTED_OUT", progression.snapshot().history().getFirst().status());
        progression.setRewardsEnabled(true);
        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        assertEquals(0, xp());
        assertEquals(1, entitlements());
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
