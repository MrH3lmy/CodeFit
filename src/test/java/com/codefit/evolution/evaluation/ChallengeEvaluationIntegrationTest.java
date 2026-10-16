package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ChallengeEvaluationIntegrationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final BundledAssets assets = new BundledAssets();
    private final DurableEvaluationRepository repository = new DurableEvaluationRepository();
    private final ChallengePresentationService presentations =
            new ChallengePresentationService(catalog, assets, CLOCK);

    private ChallengeEvaluationCoordinator coordinator() {
        return new ChallengeEvaluationCoordinator(
                catalog,
                assets,
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, CLOCK),
                CLOCK,
                b -> {});
    }

    private SubmissionRequest request(String challenge) {
        var spec =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals(challenge))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        String id = UUID.randomUUID().toString();
        presentations.present(
                new Request(
                        "p-" + id,
                        quest.ref(),
                        spec.ref(),
                        spec.cohort(),
                        Familiarity.DECLARED_UNFAMILIAR));
        String source =
                assets.read(
                        spec.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        return new SubmissionRequest(
                "s-" + id,
                "p-" + id,
                source,
                OutsideAssistance.NONE_DECLARED,
                "The operations share one synchronization boundary.");
    }

    private long rows(String table) {
        return catalog.transaction(
                c -> {
                    try (var s = c.createStatement();
                            var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        r.next();
                        return r.getLong(1);
                    }
                });
    }

    @Test
    void realSubmissionFreezesDraftAndAtomicallyProducesOneLocalAttemptAndObservation() {
        var request = request("counter-repair");
        var service = coordinator();
        long attempts = rows("problem_attempts"),
                evidence = rows("evolution_evidence_observations");
        service.prepare(request);
        presentations.saveDraft(request.presentationId(), "changed editable draft");
        var result = service.resume(request.id());
        assertEquals(Outcome.PASSED, result.outcome());
        assertEquals(Stage.FINALIZED, result.stage());
        var source = repository.find(request.id()).orElseThrow();
        assertEquals(request.artifact(), source.snapshot().request().artifact());
        assertEquals(attempts + 1, rows("problem_attempts"));
        assertEquals(evidence + 1, rows("evolution_evidence_observations"));
        assertEquals(result, coordinator().submit(request));
        assertEquals(attempts + 1, rows("problem_attempts"));
        assertEquals(evidence + 1, rows("evolution_evidence_observations"));
        catalog.transaction(
                c -> {
                    var o = repository.observation(c, source, 1);
                    assertTrue(repository.matches(c, o));
                    assertEquals(EvidenceTypes.Provenance.LOCAL_CHECK, o.provenance());
                    assertEquals(EvidenceTypes.Outcome.PASS, o.outcome());
                    assertNotNull(o.evaluation().problemAttemptId());
                    var views =
                            new EvidenceRepository()
                                    .forSkill(
                                            c,
                                            source.snapshot()
                                                    .challenge()
                                                    .targets()
                                                    .getFirst()
                                                    .skill(),
                                            DurableEvaluationRepository.mapping(source.snapshot())
                                                    .ref(),
                                            0,
                                            64);
                    assertTrue(
                            views.stream()
                                    .anyMatch(
                                            v ->
                                                    v.evidence().observation().equals(o)
                                                            && v.exclusions().isEmpty()));
                    return null;
                });
    }

    @Test
    void conflictingCommandAndUnknownPresentationFailClosed() {
        var r = request("baseline-trace");
        var s = coordinator();
        s.prepare(r);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        s.prepare(
                                new SubmissionRequest(
                                        r.id(),
                                        r.presentationId(),
                                        "different",
                                        r.outsideAssistance(),
                                        r.reflection())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        s.prepare(
                                new SubmissionRequest(
                                        UUID.randomUUID().toString(),
                                        "missing",
                                        "x",
                                        OutsideAssistance.UNKNOWN,
                                        "")));
    }

    @Test
    void keyedDiagnosticUsesReservedAssessmentAndNoJavaAttempt() {
        var r = request("baseline-trace");
        long attempts = rows("problem_attempts"), assessment = rows("assessment_attempts");
        assertEquals(Outcome.PASSED, coordinator().submit(r).outcome());
        var s = repository.find(r.id()).orElseThrow();
        assertNull(s.problemAttempt());
        assertNotNull(s.assessmentAttempt());
        assertEquals(attempts, rows("problem_attempts"));
        assertEquals(assessment + 1, rows("assessment_attempts"));
    }

    @Test
    void preparedCancellationIsUnavailableNotCapabilityFailure() {
        var r = request("counter-repair");
        var s = coordinator();
        s.prepare(r);
        s.cancel(r.id());
        assertEquals(Outcome.CANCELLED, s.resume(r.id()).outcome());
        catalog.transaction(
                c -> {
                    var source = repository.find(c, r.id()).orElseThrow();
                    assertEquals(
                            EvidenceTypes.Outcome.UNAVAILABLE,
                            repository.observation(c, source, 1).outcome());
                    return null;
                });
    }

    @Test
    void sameArtifactNewCommandIsCorrelatedRepeatNotIndependentTransfer() {
        var r = request("counter-repair");
        var s = coordinator();
        s.submit(r);
        var next =
                new SubmissionRequest(
                        UUID.randomUUID().toString(),
                        r.presentationId(),
                        r.artifact(),
                        r.outsideAssistance(),
                        r.reflection());
        s.submit(next);
        catalog.transaction(
                c -> {
                    var first = repository.find(c, r.id()).orElseThrow();
                    var repeat = repository.find(c, next.id()).orElseThrow();
                    var a = repository.observation(c, first, 1);
                    var b = repository.observation(c, repeat, 1);
                    assertEquals(EvidenceTypes.Exposure.REPEAT, b.exposure());
                    assertEquals(a.correlationKey(), b.correlationKey());
                    assertEquals(a.evaluation().artifactSha256(), b.evaluation().artifactSha256());
                    return null;
                });
    }

    @Test
    void assistanceSnapshotRetainsSolutionRevealAcrossRestart() {
        var r = request("counter-repair");
        presentations.revealSolution(r.presentationId(), UUID.randomUUID().toString());
        coordinator().submit(r);
        catalog.transaction(
                c -> {
                    var s = repository.find(c, r.id()).orElseThrow();
                    assertEquals(
                            EvidenceTypes.Assistance.EXPLANATION_OR_SOLUTION,
                            repository.observation(c, s, 1).assistance());
                    return null;
                });
    }

    @Test
    void withdrawalAndInvalidationAppendRevisionsWithoutAttemptsOrIndependentEvidence() {
        var r = request("baseline-trace");
        var s = coordinator();
        s.submit(r);
        long attempts = rows("assessment_attempts");
        var correction = UUID.randomUUID().toString();
        assertEquals(Outcome.INCONCLUSIVE, s.invalidate(r.id(), correction, false).outcome());
        assertEquals(s.status(r.id()), s.invalidate(r.id(), correction, false));
        assertTrue(s.invalidate(r.id(), UUID.randomUUID().toString(), true).withdrawn());
        assertTrue(s.submit(r).withdrawn());
        assertEquals(attempts, rows("assessment_attempts"));
        assertThrows(
                IllegalStateException.class,
                () -> s.invalidate(r.id(), UUID.randomUUID().toString(), false));
        catalog.transaction(
                c -> {
                    var source = repository.find(c, r.id()).orElseThrow();
                    assertEquals(3, source.revision());
                    assertEquals(
                            EvidenceTypes.Availability.RETRACTED,
                            repository.observation(c, source, 3).availability());
                    return null;
                });
    }

    @Test
    void everyCrashBoundaryRecoversOrReturnsCommittedResult() {
        for (var boundary : ChallengeEvaluationCoordinator.Boundary.values()) {
            var r = request("baseline-trace");
            long attempts = rows("assessment_attempts"),
                    obs = rows("evolution_evidence_observations");
            var faulty =
                    new ChallengeEvaluationCoordinator(
                            catalog,
                            assets,
                            new JavaChallengeEvaluator(new JavaCodeRunner(), assets, CLOCK),
                            CLOCK,
                            b -> {
                                if (b == boundary)
                                    throw new IllegalStateException("simulated crash " + b);
                            });
            assertThrows(IllegalStateException.class, () -> faulty.submit(r));
            var after = repository.find(r.id()).orElseThrow();
            if (boundary == ChallengeEvaluationCoordinator.Boundary.BEFORE_COMMIT) {
                assertEquals(Stage.EVALUATED, after.stage());
                assertEquals(attempts, rows("assessment_attempts"));
                assertEquals(obs, rows("evolution_evidence_observations"));
            }
            assertEquals(
                    Outcome.PASSED, coordinator().resume(r.id()).outcome(), boundary.toString());
            assertEquals(attempts + 1, rows("assessment_attempts"));
            assertEquals(obs + 1, rows("evolution_evidence_observations"));
        }
    }

    @Test
    void globalBusyReturnsPreparedWithoutAnotherExecution() {
        var r = request("baseline-trace");
        var s = coordinator();
        s.prepare(r);
        try (var busy = JavaExecutionCoordinator.tryStart(new RunCancellationToken())) {
            assertNotNull(busy);
            assertEquals(Stage.PREPARED, s.resume(r.id()).stage());
        }
        assertEquals(Outcome.PASSED, s.resume(r.id()).outcome());
    }

    @Test
    void concurrentCommandRetriesHaveOneDurableEffect() throws Exception {
        var r = request("baseline-trace");
        coordinator().prepare(r);
        long attempts = rows("assessment_attempts");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> coordinator().submit(r));
            var b = pool.submit(() -> coordinator().submit(r));
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertEquals(Outcome.PASSED, coordinator().resume(r.id()).outcome());
        assertEquals(attempts + 1, rows("assessment_attempts"));
    }

    @Test
    void boundedRecoveryPagesDoNotRescanOnStartup() {
        var r = request("baseline-trace");
        coordinator().prepare(r);
        var s = repository.find(r.id()).orElseThrow();
        assertTrue(repository.unfinished(s.id() - 1, 1).contains(r.id()));
        assertFalse(repository.unfinished(s.id(), 1).contains(r.id()));
        assertThrows(IllegalArgumentException.class, () -> repository.unfinished(0, 65));
    }

    @Test
    void schemaAndSnapshotAreImmutableAndForeignKeysClean() {
        var r = request("baseline-trace");
        coordinator().submit(r);
        catalog.transaction(
                c -> {
                    assertThrows(
                            SQLException.class,
                            () ->
                                    DurableEvaluationRepository.update(
                                            c,
                                            "UPDATE evolution_submissions SET artifact_sha='bad'"
                                                    + " WHERE command_id=?",
                                            r.id()));
                    assertThrows(
                            SQLException.class,
                            () ->
                                    DurableEvaluationRepository.update(
                                            c, "DELETE FROM evolution_evaluation_revisions"));
                    try (var q = c.createStatement();
                            var rs = q.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(rs.next());
                    }
                    return null;
                });
        DatabaseConfig.initialize();
        assertEquals(Outcome.PASSED, coordinator().status(r.id()).outcome());
    }

    @Test
    void executionHoldsNoDatabaseWriteTransactionAndCancellationPersists() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var r = request("baseline-trace");
        var engine =
                new JavaChallengeEvaluator(new JavaCodeRunner(), assets, CLOCK) {
                    @Override
                    public EvaluationResult evaluate(
                            SubmissionSnapshot snapshot, RunCancellationToken token) {
                        entered.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return super.evaluate(snapshot, token);
                    }
                };
        var service = new ChallengeEvaluationCoordinator(catalog, assets, engine, CLOCK, b -> {});
        try (var pool = Executors.newSingleThreadExecutor()) {
            var work = pool.submit(() -> service.submit(r));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                catalog.transaction(
                        c -> {
                            DurableEvaluationRepository.update(
                                    c,
                                    "INSERT INTO decks(name,description) VALUES('Independent writer"
                                            + " during evaluation','Fixture')");
                            return null;
                        });
                assertEquals(Stage.RUNNING, service.cancel(r.id()).stage());
            } finally {
                release.countDown();
            }
            assertEquals(Outcome.CANCELLED, work.get(15, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void twoDistinctPreparedCommandsRemainSeparateButCorrelated() throws Exception {
        var a = request("baseline-trace");
        var b =
                new SubmissionRequest(
                        UUID.randomUUID().toString(),
                        a.presentationId(),
                        a.artifact(),
                        a.outsideAssistance(),
                        a.reflection());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> coordinator().submit(a));
            var second = pool.submit(() -> coordinator().submit(b));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertEquals(Outcome.PASSED, coordinator().resume(a.id()).outcome());
        assertEquals(Outcome.PASSED, coordinator().resume(b.id()).outcome());
        assertNotEquals(
                repository.find(a.id()).orElseThrow().assessmentAttempt(),
                repository.find(b.id()).orElseThrow().assessmentAttempt());
    }

    @Test
    void restartRecoversDeadOwnerButNeverStealsLiveOtherProcess() {
        var dead = request("baseline-trace");
        coordinator().prepare(dead);
        assertTrue(ProcessHandle.of(99999999).isEmpty());
        catalog.transaction(
                c -> {
                    DurableEvaluationRepository.update(
                            c,
                            "UPDATE evolution_submissions SET"
                                + " state='RUNNING',execution_number=1,run_token='orphan',owner_pid=99999999,owner_started='unknown'"
                                + " WHERE command_id=?",
                            dead.id());
                    return null;
                });
        assertEquals(Outcome.PASSED, coordinator().resume(dead.id()).outcome());
        var live = request("baseline-localize");
        coordinator().prepare(live);
        var parent = ProcessHandle.current().parent().orElseThrow();
        assertTrue(parent.isAlive());
        catalog.transaction(
                c -> {
                    DurableEvaluationRepository.update(
                            c,
                            "UPDATE evolution_submissions SET"
                                + " state='RUNNING',execution_number=1,run_token='live',owner_pid=?,owner_started=?"
                                + " WHERE command_id=?",
                            parent.pid(),
                            parent.info().startInstant().map(Instant::toString).orElse("unknown"),
                            live.id());
                    return null;
                });
        assertEquals(Stage.RUNNING, coordinator().resume(live.id()).stage());
        assertEquals("live", repository.find(live.id()).orElseThrow().token());
    }

    @Test
    void staleExecutionTokenCannotStageOrFinalize() {
        var r = request("baseline-trace");
        var service =
                new ChallengeEvaluationCoordinator(
                        catalog,
                        assets,
                        new JavaChallengeEvaluator(new JavaCodeRunner(), assets, CLOCK),
                        CLOCK,
                        b -> {
                            if (b == ChallengeEvaluationCoordinator.Boundary.EXECUTED)
                                catalog.transaction(
                                        c -> {
                                            DurableEvaluationRepository.update(
                                                    c,
                                                    "UPDATE evolution_submissions SET"
                                                            + " run_token='new-fence' WHERE"
                                                            + " command_id=?",
                                                    r.id());
                                            return null;
                                        });
                        });
        long sources = rows("assessment_attempts");
        assertThrows(IllegalStateException.class, () -> service.submit(r));
        assertEquals(sources, rows("assessment_attempts"));
        assertEquals(Outcome.PASSED, coordinator().resume(r.id()).outcome());
    }

    @Test
    void repeatedInterruptionsHaveBoundedRecoveryAndProduceNoSuccess() {
        var r = request("baseline-trace");
        var service =
                new ChallengeEvaluationCoordinator(
                        catalog,
                        assets,
                        new JavaChallengeEvaluator(new JavaCodeRunner(), assets, CLOCK),
                        CLOCK,
                        b -> {
                            if (b == ChallengeEvaluationCoordinator.Boundary.CLAIMED)
                                throw new IllegalStateException("interrupted");
                        });
        for (int i = 0; i < 3; i++)
            assertThrows(IllegalStateException.class, () -> service.submit(r));
        assertEquals(Outcome.INCONCLUSIVE, coordinator().resume(r.id()).outcome());
        assertNull(repository.find(r.id()).orElseThrow().assessmentAttempt());
    }

    @Test
    void finalizedReplaySurvivesMissingInstalledAssets() {
        var r = request("baseline-trace");
        var expected = coordinator().submit(r);
        var missing =
                new BundledAssets() {
                    @Override
                    public String read(AssetRef ref) {
                        throw new IllegalStateException("asset removed");
                    }
                };
        var service =
                new ChallengeEvaluationCoordinator(
                        catalog,
                        missing,
                        new JavaChallengeEvaluator(new JavaCodeRunner(), missing, CLOCK),
                        CLOCK,
                        b -> {});
        assertEquals(expected, service.submit(r));
    }

    @Test
    void forgedArtifactDigestDoesNotValidateAsDurableEvidence() {
        var r = request("baseline-trace");
        coordinator().submit(r);
        catalog.transaction(
                c -> {
                    var s = repository.find(c, r.id()).orElseThrow();
                    var o = repository.observation(c, s, 1);
                    var e = o.evaluation();
                    var forged =
                            new EvidenceObservation(
                                    o.source(),
                                    o.time(),
                                    o.provenance(),
                                    o.outcome(),
                                    o.assistance(),
                                    o.hintLevel(),
                                    o.exposure(),
                                    o.limitations(),
                                    o.availability(),
                                    o.resultCode(),
                                    o.originCode(),
                                    o.correlationKey(),
                                    new EvaluationReference(
                                            e.submissionId(),
                                            e.familyId(),
                                            e.rubricVersion(),
                                            e.evaluatorVersion(),
                                            "0".repeat(64),
                                            e.checkIds(),
                                            e.problemAttemptId()),
                                    o.change(),
                                    o.previousEvidenceId(),
                                    o.reason(),
                                    o.actor());
                    assertFalse(repository.matches(c, forged));
                    assertTrue(repository.matches(c, o));
                    return null;
                });
    }

    @Test
    void reservedAssessmentResultsDoNotEnterWeeklyReports() {
        var stats = new AssessmentStatsService();
        var before = stats.getTransferPerformanceBySkill();
        var latest = stats.getLatestRunSummary();
        coordinator().submit(request("baseline-trace"));
        assertEquals(before, stats.getTransferPerformanceBySkill());
        assertEquals(latest, stats.getLatestRunSummary());
    }

    public static class Orphan {
        public static void main(String[] args) throws Exception {
            while (true) Thread.sleep(100);
        }
    }

    @Test
    void recoveryTerminatesPreviouslyRegisteredOrphanBeforeReexecution() throws Exception {
        var r = request("baseline-trace");
        coordinator().prepare(r);
        var child =
                new ProcessBuilder(
                                java.nio.file.Path.of(
                                                System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Orphan.class.getName())
                        .start();
        try {
            assertTrue(child.isAlive());
            String started = child.toHandle().info().startInstant().orElseThrow().toString();
            catalog.transaction(
                    c -> {
                        DurableEvaluationRepository.update(
                                c,
                                "UPDATE evolution_submissions SET"
                                    + " state='RUNNING',execution_number=1,run_token='orphan-child',owner_pid=99999999,owner_started='unknown',child_pid=?,child_started=?"
                                    + " WHERE command_id=?",
                                child.pid(),
                                started,
                                r.id());
                        return null;
                    });
            assertEquals(Outcome.PASSED, coordinator().resume(r.id()).outcome());
            assertTrue(child.waitFor(3, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void legacyAttemptAliasesRemainSuppressedAfterWithdrawal() {
        for (String challenge : List.of("counter-repair", "baseline-trace")) {
            var request = request(challenge);
            coordinator().submit(request);
            catalog.transaction(
                    c -> {
                        var source = repository.find(c, request.id()).orElseThrow();
                        var type =
                                source.problemAttempt() != null
                                        ? EvidenceTypes.SourceType.PROBLEM_ATTEMPT
                                        : EvidenceTypes.SourceType.ASSESSMENT_ATTEMPT;
                        long id =
                                source.problemAttempt() != null
                                        ? source.problemAttempt()
                                        : source.assessmentAttempt();
                        var raw =
                                new LearningEvidenceSourceRepository()
                                        .find(c, new EvidenceSourceRef(type, id, 1))
                                        .orElseThrow();
                        var mapping =
                                new EvidenceMapping(
                                        new EvidenceMappingRef(
                                                "alias-"
                                                        + UUID.randomUUID()
                                                                .toString()
                                                                .replace("-", ""),
                                                1),
                                        source.snapshot().challenge().graph(),
                                        List.of(
                                                new EvidenceMapping.Rule(
                                                        type,
                                                        raw.selector(),
                                                        source.snapshot()
                                                                .challenge()
                                                                .targets()
                                                                .getFirst()
                                                                .skill())));
                        new EvidenceMappingRepository().publish(c, mapping);
                        new EvidenceRepository()
                                .append(c, raw.observation(), mapping.ref(), raw.selector());
                        var views =
                                new EvidenceRepository()
                                        .forSkill(
                                                c,
                                                mapping.rules().getFirst().target(),
                                                mapping.ref(),
                                                0,
                                                64);
                        assertEquals(1, views.size());
                        assertTrue(
                                views.getFirst()
                                        .exclusions()
                                        .contains(
                                                EvidenceTypes.Exclusion.EVALUATION_AUTHORITATIVE));
                        assertFalse(views.getFirst().objectivelyEvaluated());
                        return null;
                    });
            coordinator().invalidate(request.id(), UUID.randomUUID().toString(), true);
            catalog.transaction(
                    c -> {
                        var source = repository.find(c, request.id()).orElseThrow();
                        assertTrue(repository.matches(c, repository.observation(c, source, 1)));
                        return null;
                    });
        }
    }

    @Test
    void preservedAttemptHistoryGapsDoNotBreakNewLocalFinalization() {
        var request = request("counter-repair");
        long problem =
                presentations.contextForSubmission(request.presentationId()).backing().problemId();
        catalog.transaction(
                c -> {
                    for (int number : List.of(1, 3))
                        DurableEvaluationRepository.update(
                                c,
                                "INSERT INTO"
                                    + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin)"
                                    + " VALUES(?,?,'WA','UNKNOWN')",
                                problem,
                                number);
                    return null;
                });
        assertEquals(Outcome.PASSED, coordinator().submit(request).outcome());
        var attempts = new ProblemAttemptRepository().findByProblemId(problem);
        assertEquals(
                List.of(1, 3, 4),
                attempts.stream().map(com.codefit.model.ProblemAttempt::attemptNumber).toList());
        assertEquals(
                com.codefit.model.SubmissionResult.LOCAL_EVALUATION,
                attempts.getLast().submissionResult());
    }

    @Test
    void legacySessionExplanationRemainsAssistedInFrozenEvaluation() {
        var request = request("counter-repair");
        long problem =
                presentations.contextForSubmission(request.presentationId()).backing().problemId();
        catalog.transaction(
                c -> {
                    DurableEvaluationRepository.update(
                            c,
                            "UPDATE problem_solving_sessions SET"
                                    + " highest_hint_level_opened='EXPLANATION' WHERE problem_id=?",
                            problem);
                    return null;
                });
        coordinator().prepare(request);
        coordinator().resume(request.id());
        catalog.transaction(
                c -> {
                    var source = repository.find(c, request.id()).orElseThrow();
                    assertEquals(
                            com.codefit.model.HintLevel.EXPLANATION,
                            source.snapshot().legacyHint());
                    assertEquals(
                            EvidenceTypes.Assistance.EXPLANATION_OR_SOLUTION,
                            repository.observation(c, source, 1).assistance());
                    return null;
                });
    }

    @Test
    void estimationInputIsVersionedMetadataWithoutSourceOrHiddenCases() {
        var request = request("counter-repair");
        coordinator().prepare(request);
        assertTrue(repository.evaluationInput(request.id()).isEmpty());
        coordinator().resume(request.id());
        var input = repository.evaluationInput(request.id()).orElseThrow();
        assertEquals(new CatalogRef("counter-repair", 1), input.challenge());
        assertEquals(Cohort.PRACTICE, input.cohort());
        assertEquals(EvidenceTypes.Outcome.PASS, input.observation().outcome());
        assertTrue(
                input.checks().stream()
                        .filter(DurableEvaluationRepository.CheckSummary::required)
                        .allMatch(c -> c.status() == CheckStatus.PASSED));
        assertFalse(input.toString().contains("class Exercise"));
        assertFalse(input.toString().contains("expectedStdout"));
        coordinator().invalidate(request.id(), UUID.randomUUID().toString(), true);
        var withdrawn = repository.evaluationInput(request.id()).orElseThrow();
        assertEquals(EvidenceTypes.Availability.RETRACTED, withdrawn.observation().availability());
        assertTrue(withdrawn.checks().stream().noneMatch(c -> c.status() == CheckStatus.PASSED));
    }
}
