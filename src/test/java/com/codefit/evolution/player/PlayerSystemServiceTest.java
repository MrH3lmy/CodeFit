package com.codefit.evolution.player;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;
import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.ui.NavigationService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PlayerSystemServiceTest {
    @TempDir Path dir;
    String previous;

    @BeforeEach
    void fresh() {
        previous = DatabaseConfig.currentDatabaseUrl();
        DatabaseConfig.useDatabaseFile(dir.resolve("player.db"));
        DatabaseConfig.initialize();
    }

    @AfterEach
    void restore() {
        DatabaseConfig.useDatabaseUrl(previous);
        JavaExecutionCoordinator.cancelActiveAndAwait(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private PlayerSystemService service() {
        return new PlayerSystemService(
                Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private static CatalogRef ref(String id) {
        return new CatalogRef(id, 1);
    }

    private static String solution(String id) {
        var spec = new QuestCatalogRepository().findChallenge(ref(id)).orElseThrow();
        return new BundledAssets()
                .read(
                        spec.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
    }

    private long count(String table) throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void freshOfflineOverviewAndSkipKeepAllSkillsUnknownAcrossRestart() throws Exception {
        var player = service();
        var overview = player.overview();
        assertEquals(4, overview.estimates().size());
        assertEquals(3, overview.baseline().diagnostics().size());
        assertTrue(
                overview.estimates().stream()
                        .allMatch(r -> r.estimate().band() == MasteryTypes.Band.UNKNOWN));
        assertEquals(20, overview.decision().input().preferences().budgetMinutes());
        player.baseline().skipAll("skip-all");
        DatabaseConfig.initialize();
        assertTrue(service().overview().baseline().skipped());
        assertEquals(0, count("evolution_submissions"));
        assertEquals(0, count("evolution_challenge_presentations"));
        assertTrue(
                service().overview().estimates().stream()
                        .allMatch(r -> r.estimate().band() == MasteryTypes.Band.UNKNOWN));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT MAX(version),COUNT(*) FROM schema_migrations WHERE"
                                        + " version=19")) {
            r.next();
            assertEquals(19, r.getInt(1));
            assertEquals(1, r.getInt(2));
        }
    }

    @Test
    void realJavaLoopPersistsDraftFrozenArtifactResultEvidenceAndNextChoice() throws Exception {
        var player = service();
        var opened =
                player.openBaseline(
                        ref("baseline-counter"), Familiarity.DECLARED_UNFAMILIAR, false);
        assertEquals(1, count("evolution_challenge_presentations"));
        assertFalse(opened.source().contains("public class Main"));
        String correct = solution("baseline-counter");
        player.save(
                new PlayerSystemService.Draft(
                        opened.presentationId(),
                        correct,
                        "Used one monitor",
                        OutsideAssistance.NONE_DECLARED));
        var resumed = service().resume(opened.presentationId());
        assertEquals(correct, resumed.source());
        assertEquals("Used one monitor", resumed.reflection());
        var command =
                new SubmissionRequest(
                        "real-baseline",
                        opened.presentationId(),
                        correct,
                        OutsideAssistance.NONE_DECLARED,
                        "Used one monitor");
        var result = player.submit(command);
        assertEquals(Stage.FINALIZED, result.stage());
        assertEquals(Outcome.PASSED, result.outcome());
        assertTrue(
                result.checks().stream()
                        .noneMatch(c -> c.feedback().contains("public class Main")));
        assertEquals(1, count("problem_attempts"));
        assertEquals(1, count("evolution_evidence_observations"));
        player.save(
                new PlayerSystemService.Draft(
                        opened.presentationId(),
                        "changed draft",
                        "new notes",
                        OutsideAssistance.DECLARED));
        assertEquals(result, service().submit(command));
        assertEquals(
                correct,
                new DurableEvaluationRepository()
                        .find(command.id())
                        .orElseThrow()
                        .snapshot()
                        .request()
                        .artifact());
        assertEquals(1, count("problem_attempts"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.submit(
                                new SubmissionRequest(
                                        command.id(),
                                        command.presentationId(),
                                        "different payload",
                                        OutsideAssistance.NONE_DECLARED,
                                        "")));
        var after = service().overview();
        assertEquals(
                MasteryTypes.Band.DEVELOPING,
                after.estimates().stream()
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
        assertNotNull(after.decision());
        // Explicitly choose a legitimate practice opportunity; finite prerequisite coverage is not
        // bypassed silently.
        player.adaptive()
                .respond(
                        new LearnerCommand(
                                "budget",
                                CommandKind.CHANGE_BUDGET,
                                null,
                                null,
                                null,
                                50,
                                null,
                                null));
        var decision = service().overview().decision();
        var candidate =
                decision.input().candidates().stream()
                        .filter(c -> c.challenge().id().equals("counter-repair"))
                        .findFirst()
                        .orElseThrow();
        var ranking =
                decision.result().candidates().stream()
                        .filter(r -> r.candidateKey().equals(candidate.key()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(ranking.exploratoryEligible());
        var choose =
                new LearnerCommand(
                        "explore-counter",
                        CommandKind.EXPLORE,
                        decision.id(),
                        candidate.challenge(),
                        null,
                        null,
                        null,
                        null);
        player.adaptive().respond(choose);
        assertEquals(1, count("evolution_challenge_presentations"));
        var next = service().start(choose.id(), Familiarity.DECLARED_UNFAMILIAR);
        assertEquals(ref("counter-repair"), next.challenge());
        assertEquals(2, count("evolution_challenge_presentations"));
        assertEquals(next, service().start(choose.id(), Familiarity.DECLARED_UNFAMILIAR));
    }

    @Test
    void keyedResponseAndNotesResumeWithoutAnotherAttemptOrExposure() throws Exception {
        var player = service();
        var opened = player.openBaseline(ref("baseline-trace"), Familiarity.UNKNOWN, false);
        assertFalse(opened.java());
        player.save(
                new PlayerSystemService.Draft(
                        opened.presentationId(),
                        "my unfinished response",
                        "reasoning",
                        OutsideAssistance.DECLARED));
        DatabaseConfig.initialize();
        var resumed = service().openBaseline(ref("baseline-trace"), Familiarity.UNKNOWN, true);
        assertEquals("my unfinished response", resumed.source());
        assertEquals("reasoning", resumed.reflection());
        assertEquals(OutsideAssistance.DECLARED, resumed.assistance());
        assertEquals(1, count("evolution_challenge_presentations"));
        assertEquals(0, count("assessment_attempts"));
    }

    @Test
    void publicPracticeDoesNotPublishEvidenceAndHintsRemainNonIndependent() throws Exception {
        var player = service();
        var opened =
                player.openBaseline(
                        ref("baseline-counter"), Familiarity.DECLARED_UNFAMILIAR, false);
        var practice =
                player.practice(
                        opened.presentationId(),
                        "public-practice",
                        solution("baseline-counter"),
                        new RunCancellationToken());
        assertEquals(
                CheckStatus.NOT_RUN,
                player.practice(
                                opened.presentationId(),
                                "public-practice",
                                solution("baseline-counter"),
                                new RunCancellationToken())
                        .getFirst()
                        .status());
        assertFalse(practice.isEmpty());
        assertTrue(practice.stream().allMatch(c -> c.status() == CheckStatus.PASSED));
        assertEquals(0, count("evolution_submissions"));
        assertEquals(0, count("evolution_evidence_observations"));
        assertEquals(0, count("problem_attempts"));
        player.hint(opened.presentationId(), "hint-first", 1);
        player.hint(opened.presentationId(), "hint-first", 1);
        var result =
                player.submit(
                        new SubmissionRequest(
                                "assisted",
                                opened.presentationId(),
                                solution("baseline-counter"),
                                OutsideAssistance.NONE_DECLARED,
                                ""));
        assertEquals(Outcome.PASSED, result.outcome());
        var input = player.resultContext(result.command()).orElseThrow();
        assertEquals(
                com.codefit.evolution.evidence.EvidenceTypes.Assistance.HINT,
                input.observation().assistance());
        var after = service().overview();
        assertTrue(
                after.estimates().stream().allMatch(r -> r.estimate().independentFamilies() == 0));
        assertEquals(2, count("evolution_challenge_exposure_events"));
    }

    @Test
    void checkpointCancellationAndInterruptedPreparedRecoveryNeverInferSuccess() throws Exception {
        var player = service();
        var opened = player.openBaseline(ref("baseline-trace"), Familiarity.UNKNOWN, false);
        var cancelled =
                player.submit(
                        new SubmissionRequest(
                                "cancel-before-execution",
                                opened.presentationId(),
                                "anything",
                                OutsideAssistance.UNKNOWN,
                                ""),
                        () -> true);
        assertEquals(Outcome.CANCELLED, cancelled.outcome());
        var prepared =
                new SubmissionRequest(
                        "interrupted",
                        opened.presentationId(),
                        "final=1;serial=2;cause=atomicity",
                        OutsideAssistance.NONE_DECLARED,
                        "");
        player.baseline().prepare(opened.challenge(), prepared);
        assertEquals(Stage.PREPARED, service().evaluation().status(prepared.id()).stage());
        assertEquals(
                Outcome.PASSED, service().baseline().resumeSubmission(prepared.id()).outcome());
        assertEquals(Outcome.PASSED, service().evaluation().resume(prepared.id()).outcome());
        assertEquals(2, count("evolution_submissions"));
        assertEquals(1, count("assessment_attempts"));
    }

    @Test
    void derivationFailureAfterCommitDoesNotEraseResultOrRequireResubmission() throws Exception {
        var broken =
                new PlayerSystemService(
                        Clock.systemUTC(),
                        new ChallengePresentationService(),
                        new AdaptiveQuestService(),
                        new ChallengeEvaluationCoordinator(),
                        () -> {
                            throw new IllegalStateException("bounded adaptation unavailable");
                        });
        var open =
                broken.openBaseline(ref("baseline-trace"), Familiarity.DECLARED_UNFAMILIAR, false);
        var result =
                broken.submit(
                        new SubmissionRequest(
                                "committed",
                                open.presentationId(),
                                "final=1;serial=2;cause=atomicity",
                                OutsideAssistance.NONE_DECLARED,
                                ""));
        assertEquals(Outcome.PASSED, result.outcome());
        assertThrows(IllegalStateException.class, broken::overview);
        assertEquals(result, service().evaluation().status(result.command()));
        assertEquals(1, count("evolution_evidence_observations"));
        assertNotNull(service().overview().decision());
        assertEquals(1, count("assessment_attempts"));
    }

    @Test
    void declineDeferPauseAndResumePersistWithoutPerformanceEvents() throws Exception {
        var player = service();
        var initial = player.overview().decision();
        var selected = initial.result().primary();
        assertNotNull(selected);
        player.adaptive()
                .respond(
                        new LearnerCommand(
                                "defer",
                                CommandKind.DEFER,
                                initial.id(),
                                selected.challenge(),
                                null,
                                null,
                                null,
                                Instant.now().plusSeconds(86400)));
        var restarted = service().overview();
        assertTrue(
                restarted
                        .decision()
                        .input()
                        .preferences()
                        .deferred()
                        .containsKey(selected.challenge().id()));
        player.adaptive()
                .respond(
                        new LearnerCommand(
                                "decline",
                                CommandKind.DECLINE,
                                initial.id(),
                                selected.challenge(),
                                null,
                                null,
                                null,
                                null));
        assertTrue(
                service()
                        .overview()
                        .decision()
                        .input()
                        .preferences()
                        .declined()
                        .contains(selected.challenge().id()));
        player.adaptive().respond(LearnerCommand.simple("pause", CommandKind.PAUSE));
        assertEquals(Branch.PAUSED, service().overview().decision().result().branch());
        player.adaptive().respond(LearnerCommand.simple("resume", CommandKind.RESUME));
        assertEquals(0, count("evolution_submissions"));
        assertEquals(0, count("evolution_challenge_presentations"));
        assertEquals(0, count("evolution_evidence_observations"));
    }

    @Test
    void holdoutCannotOpenThroughGenericWorkspaceEvenAfterAuthorizedExposure() {
        var spec =
                new QuestCatalogRepository().findChallenge(ref("inventory-transfer")).orElseThrow();
        var backing =
                new QuestCatalogRepository()
                        .transaction(c -> new QuestCatalogRepository().backing(c, spec.ref()));
        assertThrows(
                IllegalArgumentException.class,
                () -> NavigationService.showSolvingWorkspace(backing.problemId()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service().resume("not-an-authorized-receipt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service().baseline().present(ref("inventory-transfer"), Familiarity.UNKNOWN));
    }
}
