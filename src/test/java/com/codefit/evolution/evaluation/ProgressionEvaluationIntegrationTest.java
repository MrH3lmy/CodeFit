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
        coordinator.prepare(request);
        progression.setRewardsEnabled(false);

        assertEquals(Outcome.PASSED, coordinator.submit(request).outcome());
        progression.setRewardsEnabled(true);
        assertEquals(0, progression.reconcilePendingEffortRewards());
        assertEquals(0, xp());
        assertEquals("OPTED_OUT", progression.snapshot().history().getFirst().status());
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT disposition FROM evolution_reward_effort_processed"
                                        + " WHERE submission_id=(SELECT id FROM"
                                        + " evolution_submissions WHERE command_id=?)")) {
            s.setString(1, request.id());
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assertEquals("OPTED_OUT", r.getString(1));
            }
        }
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

    @Test
    void olderPreparedSubmissionIsReconciledAfterNewerObjectiveFinalizesFirst() throws Exception {
        var olderChallenge =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var olderQuest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(olderChallenge.ref()))
                        .findFirst()
                        .orElseThrow();
        var assets = new BundledAssets();
        String olderPresentation = "out-of-order-" + UUID.randomUUID();
        new ChallengePresentationService(new QuestCatalogRepository(), assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                olderPresentation,
                                olderQuest.ref(),
                                olderChallenge.ref(),
                                olderChallenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String olderSource =
                assets.read(
                        olderChallenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var olderRequest =
                new SubmissionRequest(
                        "out-of-order-older-" + UUID.randomUUID(),
                        olderPresentation,
                        olderSource,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        var coordinator = new ChallengeEvaluationCoordinator(CLOCK);
        coordinator.prepare(olderRequest);

        var newer = submitReference("queue-retention");
        assertEquals(Outcome.PASSED, newer.outcome());
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());

        assertEquals(Outcome.PASSED, coordinator.resume(olderRequest.id()).outcome());
        var restarted = new ProgressionService();
        assertEquals(0, restarted.reconcilePendingEffortRewards());
        assertEquals(20, xp());
        assertEquals(2, entitlements());
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(20, xp());
        assertEquals(2, entitlements());
    }

    @Test
    void boundedEffortRecoveryAdvancesPastIneligibleSubmissionsAcrossRestart() throws Exception {
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
        String presentation = "starvation-" + UUID.randomUUID();
        var assets = new BundledAssets();
        new ChallengePresentationService(new QuestCatalogRepository(), assets, CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                challenge.ref(),
                                challenge.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        seedIneligibleSubmissions(presentation, 513);

        String solution =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "starvation-eligible-" + UUID.randomUUID(),
                        presentation,
                        solution,
                        OutsideAssistance.NONE_DECLARED,
                        "");
        assertEquals(Outcome.PASSED, new ChallengeEvaluationCoordinator(CLOCK).submit(request).outcome());

        var firstRefresh = new ProgressionService();
        assertEquals(1, firstRefresh.snapshot().pendingEffortRewards());
        assertEquals(0, xp());
        assertEquals(0, entitlements());
        assertEquals(0, firstRefresh.reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());

        var afterRestart = new ProgressionService();
        assertEquals(0, afterRestart.reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
    }

    @Test
    void corruptFinalizedEffortSourceIsQuarantinedWithoutBlockingLaterRewards() throws Exception {
        var corrupt = submitReference("counter-repair");
        var valid = submitReference("queue-retention");
        tamperFinalizedEvaluationDigest(corrupt.command());

        try (var c = DatabaseConfig.getConnection(); var statement = c.createStatement()) {
            statement.execute(
                    "CREATE TRIGGER transient_xp_failure BEFORE UPDATE OF xp ON user_progress"
                            + " BEGIN SELECT RAISE(ABORT,'temporary XP write failure'); END");
        }
        var service = new ProgressionService();
        assertEquals(1, service.reconcilePendingEffortRewards());
        assertEquals(0, xp());
        assertEquals(0, entitlements());
        assertEquals(1, service.snapshot().quarantinedEffortSources());
        assertEquals(
                "FINALIZED_EVALUATION_DIGEST_MISMATCH",
                effortFailureCode(corrupt.command()));
        try (var c = DatabaseConfig.getConnection(); var statement = c.createStatement()) {
            statement.execute("DROP TRIGGER transient_xp_failure");
        }
        var restarted = new ProgressionService();
        assertEquals(0, restarted.reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
        try (var c = DatabaseConfig.getConnection();
                var statement =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM evolution_reward_entitlements"
                                        + " WHERE source_ref=?")) {
            statement.setString(1, "submission:" + valid.command());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
        assertEquals(0, restarted.snapshot().pendingEffortRewards());
    }

    @Test
    void undecodableFinalizedEffortSourceIsQuarantinedRatherThanMarkedIneligible()
            throws Exception {
        var corrupt = submitReference("counter-repair");
        submitReference("queue-retention");
        tamperFinalizedEvaluationPayload(corrupt.command(), new byte[] {1});

        assertEquals(0, new ProgressionService().reconcilePendingEffortRewards());
        assertEquals(10, xp());
        assertEquals(1, entitlements());
        assertEquals(
                "FINALIZED_EVALUATION_PAYLOAD_INVALID",
                effortFailureCode(corrupt.command()));
        assertEquals(1, new ProgressionService().snapshot().quarantinedEffortSources());
    }

    @Test
    void quarantineMigrationPreservesExistingEffortMarkers() throws Exception {
        var ineligible = submitReference("counter-repair");
        var next = submitReference("queue-retention");
        try (var c = DatabaseConfig.getConnection(); var statement = c.createStatement()) {
            statement.execute("DROP TABLE evolution_reward_effort_processed");
            statement.execute(
                    """
                    CREATE TABLE evolution_reward_effort_processed (
                      submission_id INTEGER PRIMARY KEY REFERENCES evolution_submissions(id),
                      disposition TEXT NOT NULL CHECK(disposition IN('RECONCILED','INELIGIBLE'))
                    )
                    """);
            try (var insert =
                    c.prepareStatement(
                            "INSERT INTO evolution_reward_effort_processed"
                                    + "(submission_id,disposition)"
                                    + " SELECT id,'INELIGIBLE' FROM evolution_submissions"
                                    + " WHERE command_id=?")) {
                insert.setString(1, ineligible.command());
                assertEquals(1, insert.executeUpdate());
            }
            statement.execute("DELETE FROM schema_migrations WHERE version IN(24,25)");
        }

        DatabaseConfig.initialize();

        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT x.disposition,x.failure_code FROM"
                                        + " evolution_reward_effort_processed x"
                                        + " JOIN evolution_submissions e ON e.id=x.submission_id"
                                        + " WHERE e.command_id=?")) {
            s.setString(1, ineligible.command());
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assertEquals("INELIGIBLE", r.getString(1));
                assertNull(r.getString(2));
            }
        }
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "INSERT INTO evolution_reward_effort_processed"
                                        + "(submission_id,disposition,failure_code)"
                                        + " SELECT id,'QUARANTINED',"
                                        + "'FINALIZED_EVALUATION_PAYLOAD_INVALID'"
                                        + " FROM evolution_submissions WHERE command_id=?")) {
            s.setString(1, next.command());
            assertEquals(1, s.executeUpdate());
        }
    }

    private void tamperFinalizedEvaluationDigest(String commandId) throws Exception {
        tamperFinalizedEvaluation(commandId, null, "damaged-digest");
    }

    private void tamperFinalizedEvaluationPayload(String commandId, byte[] payload)
            throws Exception {
        tamperFinalizedEvaluation(commandId, payload, null);
    }

    private void tamperFinalizedEvaluation(String commandId, byte[] payload, String digest)
            throws Exception {
        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute("DROP TRIGGER evolution_evaluation_revisions_no_update");
            if (payload == null) {
                try (var update =
                        c.prepareStatement(
                                "UPDATE evolution_evaluation_revisions SET digest=?"
                                        + " WHERE submission_id=(SELECT id FROM"
                                        + " evolution_submissions WHERE command_id=?)")) {
                    update.setString(1, digest);
                    update.setString(2, commandId);
                    assertEquals(1, update.executeUpdate());
                }
            } else {
                try (var update =
                        c.prepareStatement(
                                "UPDATE evolution_evaluation_revisions SET payload=?,digest=?"
                                        + " WHERE submission_id=(SELECT id FROM"
                                        + " evolution_submissions WHERE command_id=?)")) {
                    update.setBytes(1, payload);
                    update.setString(2, CatalogCodec.digest(payload));
                    update.setString(3, commandId);
                    assertEquals(1, update.executeUpdate());
                }
            }
            s.execute(
                    "CREATE TRIGGER evolution_evaluation_revisions_no_update BEFORE UPDATE"
                            + " ON evolution_evaluation_revisions BEGIN SELECT"
                            + " RAISE(ABORT,'Evaluation history is immutable'); END");
        }
    }

    private String effortFailureCode(String commandId) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                """
                                SELECT x.failure_code
                                FROM evolution_reward_effort_processed x
                                JOIN evolution_submissions e ON e.id=x.submission_id
                                WHERE e.command_id=? AND x.disposition='QUARANTINED'
                                """)) {
            s.setString(1, commandId);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private void seedIneligibleSubmissions(String presentation, int count) throws Exception {
        var unavailable =
                new EvaluationResult(
                        Outcome.TOOL_UNAVAILABLE,
                        java.util.List.of(),
                        "fixture",
                        CLOCK.instant(),
                        CLOCK.instant(),
                        "fixture",
                        "fixture");
        byte[] payload = EvaluationCodec.encode(unavailable);
        String digest = CatalogCodec.digest(payload);
        try (var c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            try (var submission =
                            c.prepareStatement(
                                    """
                                    INSERT INTO evolution_submissions
                                      (command_id,presentation_id,request_sha,snapshot,snapshot_sha,
                                       artifact_sha,executable_sha,family_id,variant_id,state,final_revision)
                                    VALUES(?,?,?,X'01',?,?,?,?,?,'FINALIZED',1)
                                    """);
                    var revision =
                            c.prepareStatement(
                                    """
                                    INSERT INTO evolution_evaluation_revisions
                                      (submission_id,revision,correction_id,payload,digest,
                                       change_kind,reason,actor)
                                    VALUES(?,1,?,?,?,'ORIGINAL','fixture','SYSTEM')
                                    """)) {
                for (int i = 0; i < count; i++) {
                    String command = "ineligible-" + UUID.randomUUID();
                    submission.setString(1, command);
                    submission.setString(2, presentation);
                    submission.setString(3, "request-" + i);
                    submission.setString(4, "snapshot-" + i);
                    submission.setString(5, "artifact-" + i);
                    submission.setString(6, "executable-" + i);
                    submission.setString(7, "family-" + i);
                    submission.setString(8, "variant-" + i);
                    submission.executeUpdate();
                    long id;
                    try (var s = c.createStatement();
                            var r = s.executeQuery("SELECT last_insert_rowid()")) {
                        assertTrue(r.next());
                        id = r.getLong(1);
                    }
                    revision.setLong(1, id);
                    revision.setString(2, command);
                    revision.setBytes(3, payload);
                    revision.setString(4, digest);
                    revision.executeUpdate();
                }
                c.commit();
            } catch (Exception failure) {
                c.rollback();
                throw failure;
            }
        }
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
