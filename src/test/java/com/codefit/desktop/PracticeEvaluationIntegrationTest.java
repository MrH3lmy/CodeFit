package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evaluation.EvaluationTypes.*;
import com.codefit.evolution.quest.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class PracticeEvaluationIntegrationTest {
    @TempDir Path temporary;
    String previous;

    @BeforeEach
    void before() {
        previous = DatabaseConfig.currentDatabaseUrl();
    }

    @AfterEach
    void after() {
        DatabaseConfig.useDatabaseUrl(previous);
    }

    String id() {
        return UUID.randomUUID().toString();
    }

    String open(ExperimentalProfile p) throws Exception {
        var app = new PracticeApplicationService(p, Clock.systemUTC());
        var challenge = new CatalogRef("regression-localize", 1);
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(challenge))
                        .findFirst()
                        .orElseThrow();
        return app.open(id(), quest.ref(), challenge).receipt().presentationId();
    }

    String reference() {
        var spec =
                JavaTrainingPack.challenges().stream()
                        .filter(x -> x.ref().id().equals("regression-localize"))
                        .findFirst()
                        .orElseThrow();
        return new BundledAssets()
                .read(
                        spec.assets().stream()
                                .filter(a -> a.kind() == QuestTypes.AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
    }

    PracticeDraftService drafts(ExperimentalProfile p) {
        return new PracticeDraftService(p, Clock.systemUTC());
    }

    PracticeDraftService.Draft valid(ExperimentalProfile p, String presentation) throws Exception {
        var d = drafts(p).read(presentation);
        drafts(p).save(id(), presentation, d.revision(), reference(), "Synthetic reflection");
        return drafts(p).read(presentation);
    }

    long count(ExperimentalProfile p, String table) throws Exception {
        try (var c = p.connection(true);
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    PracticeEvaluationService.Result await(
            PracticeEvaluationService e, String command, String presentation) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < end) {
            var r = e.status(command, presentation);
            if (!r.active() && r.view().stage() == Stage.FINALIZED) return r;
            Thread.sleep(30);
        }
        fail("Bounded evaluation did not finalize");
        return null;
    }

    @Test
    void exactSavedArtifactFinalizesOnceDespiteLaterDraftAndReplay() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            var prepared = e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            assertEquals(Stage.PREPARED, prepared.view().stage());
            assertEquals(0, count(p, "problem_attempts"));
            drafts(p)
                    .save(
                            id(),
                            presentation,
                            d.revision(),
                            "class LaterDraft {}",
                            "Later reflection");
            assertEquals(prepared, e.prepare(command, presentation, d.revision(), "NONE_DECLARED"));
            var frozen = new DurableEvaluationRepository().find(command).orElseThrow().snapshot();
            assertEquals(d.source(), frozen.request().artifact());
            assertEquals(d.reflection(), frozen.request().reflection());
            e.resume(command, presentation);
            var result = await(e, command, presentation);
            assertEquals(Outcome.PASSED, result.view().outcome());
            assertEquals(1, count(p, "problem_attempts"));
            assertEquals(1, count(p, "evolution_evaluation_revisions"));
            long observations = count(p, "evolution_evidence_observations");
            assertTrue(observations > 0);
            assertEquals(result, e.resume(command, presentation));
            assertEquals(result, e.cancel(command, presentation));
            assertEquals(observations, count(p, "evolution_evidence_observations"));
            assertEquals(1, count(p, "problem_attempts"));
            assertEquals("class LaterDraft {}", drafts(p).read(presentation).source());
            assertFalse(result.view().toString().contains("Synthetic reflection"));
        }
    }

    @Test
    void staleAndConflictingInputsNeverFreezeLatestDraft() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = drafts(p).read(presentation);
            var current = valid(p, presentation);
            assertEquals(
                    "STALE_REVISION",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> e.prepare(command, presentation, d.revision(), "UNKNOWN"))
                            .getMessage());
            assertNull(e.status(command, presentation));
            assertEquals(0, count(p, "evolution_submissions"));
            e.prepare(command, presentation, current.revision(), "DECLARED");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> e.prepare(command, presentation, current.revision(), "NONE_DECLARED"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> e.prepare(command, presentation, current.revision() + 1, "DECLARED"));
            assertEquals(
                    "BUSY",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            e.prepare(
                                                    id(),
                                                    presentation,
                                                    current.revision(),
                                                    "UNKNOWN"))
                            .getMessage());
            assertEquals(1, count(p, "evolution_submissions"));
        }
    }

    @Test
    void failedReceiptInsertRollsBackPreparationAndMapping() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            long mappings = count(p, "evolution_evidence_mappings");
            try (var c = p.connection(false);
                    var s = c.createStatement()) {
                s.execute(
                        "CREATE TRIGGER injected BEFORE INSERT ON desktop_submission_commands BEGIN"
                                + " SELECT RAISE(ABORT,'test failure'); END");
            }
            assertThrows(
                    Exception.class,
                    () -> e.prepare(command, presentation, d.revision(), "UNKNOWN"));
            assertEquals(0, count(p, "evolution_submissions"));
            assertEquals(mappings, count(p, "evolution_evidence_mappings"));
            assertNull(e.status(command, presentation));
        }
    }

    @Test
    void restartRetainsPreparedBytesAndExplicitCancellationFinalizes() throws Exception {
        String presentation, command = id();
        long revision;
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            presentation = open(p);
            var d = valid(p, presentation);
            revision = d.revision();
            e.prepare(command, presentation, revision, "DECLARED");
        }
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            new PracticeApplicationService(p, Clock.systemUTC());
            assertEquals(revision, e.recent(presentation).getFirst().draftRevision());
            assertEquals(Stage.PREPARED, e.status(command, presentation).view().stage());
            e.cancel(command, presentation);
            assertEquals(Outcome.CANCELLED, await(e, command, presentation).view().outcome());
            assertEquals(1, count(p, "evolution_challenge_presentations"));
            assertEquals(1, count(p, "problem_attempts"));
        }
    }

    @Test
    void stagedReportAndFinalizedReceiptRecoverWithoutReexecution() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            EvaluationTestFixtures.interruptAfterStaging(Clock.systemUTC())
                                    .resume(command));
            assertEquals(Stage.EVALUATED, e.status(command, presentation).view().stage());
            long runs = count(p, "evolution_evaluation_runs");
            e.resume(command, presentation);
            assertEquals(Outcome.PASSED, await(e, command, presentation).view().outcome());
            assertEquals(runs, count(p, "evolution_evaluation_runs"));
            e.resume(command, presentation);
            assertEquals(1, count(p, "evolution_evaluation_revisions"));
        }
    }

    @Test
    void unavailableToolIsNotLearnerFailureAndWithdrawnResultStaysWithdrawn() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "UNKNOWN");
            EvaluationTestFixtures.unavailableRunner(Clock.systemUTC()).resume(command);
            assertEquals(
                    Outcome.TOOL_UNAVAILABLE, e.status(command, presentation).view().outcome());
            new ChallengeEvaluationCoordinator().invalidate(command, id(), true);
            assertTrue(e.status(command, presentation).view().withdrawn());
            assertEquals(Outcome.INCONCLUSIVE, e.resume(command, presentation).view().outcome());
        }
    }

    @Test
    void unknownPresentationAndNonDesktopSubmissionAreInaccessible() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            assertThrows(
                    Exception.class,
                    () -> e.prepare(command, "desktop-practice-" + id(), d.revision(), "UNKNOWN"));
            new ChallengeEvaluationCoordinator()
                    .prepare(
                            new SubmissionRequest(
                                    command,
                                    presentation,
                                    d.source(),
                                    OutsideAssistance.UNKNOWN,
                                    ""));
            assertThrows(IllegalArgumentException.class, () -> e.status(command, presentation));
            assertEquals(0, e.recent(presentation).size());
        }
    }

    @Test
    void everyUnreviewedSkillAndStageIsRejectedWithoutPreparation() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            var presentation = open(p);
            var d = drafts(p).read(presentation);
            int count = 0;
            for (var skill : com.codefit.knowledge.KnowledgePreview.loadBundled().skills()) {
                assertThrows(
                        Exception.class,
                        () -> e.prepare(id(), skill.id(), d.revision(), "UNKNOWN"));
                for (var stage : skill.stages()) {
                    assertThrows(
                            Exception.class,
                            () -> e.prepare(id(), stage.id(), d.revision(), "UNKNOWN"));
                    count++;
                }
            }
            assertEquals(16, count);
            assertEquals(0, count(p, "evolution_submissions"));
        }
    }

    @Test
    void concurrentPrepareCommandsCannotCreateTwoUnfinishedSubmissions() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p);
            var d = valid(p, presentation);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate = new java.util.concurrent.CountDownLatch(1);
                var jobs = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 2; i++)
                    jobs.add(
                            pool.submit(
                                    () -> {
                                        gate.await();
                                        try {
                                            e.prepare(id(), presentation, d.revision(), "UNKNOWN");
                                            return true;
                                        } catch (IllegalArgumentException ex) {
                                            assertEquals("BUSY", ex.getMessage());
                                            return false;
                                        }
                                    }));
                gate.countDown();
                int wins = 0;
                for (var job : jobs) if (job.get(10, java.util.concurrent.TimeUnit.SECONDS)) wins++;
                assertEquals(1, wins);
                assertEquals(1, count(p, "evolution_submissions"));
            }
        }
    }

    @Test
    void corruptionAndOversizedSnapshotFailClosed() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "UNKNOWN");
            // Simulate on-disk damage, not a supported mutation route.
            try (var c = p.connection(false);
                    var s = c.createStatement()) {
                var names = new ArrayList<String>();
                try (var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='trigger' AND"
                                        + " tbl_name='evolution_submissions'")) {
                    while (r.next()) names.add(r.getString(1));
                }
                for (var name : names) s.execute("DROP TRIGGER " + name);
                assertThrows(
                        java.sql.SQLException.class,
                        () ->
                                s.executeUpdate(
                                        "UPDATE evolution_submissions SET"
                                                + " snapshot=zeroblob(2000001)"));
                s.execute("PRAGMA ignore_check_constraints=ON");
                s.executeUpdate("UPDATE evolution_submissions SET snapshot=zeroblob(2000001)");
            }
            assertThrows(Exception.class, () -> e.status(command, presentation));
            assertThrows(Exception.class, () -> e.resume(command, presentation));
            assertEquals(0, count(p, "problem_attempts"));
        }
    }
}
