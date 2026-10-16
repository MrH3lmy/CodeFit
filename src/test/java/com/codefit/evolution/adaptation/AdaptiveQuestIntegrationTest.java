package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationDecisionRepository.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class AdaptiveQuestIntegrationTest {
    static final Instant T = Instant.parse("2026-06-12T12:00:00Z");
    static final SkillRef ATOMIC = new SkillRef("java.concurrency.atomic-update", 1);
    @TempDir Path directory;
    final BundledAssets assets = new BundledAssets();

    @BeforeEach
    void freshDb() {
        DatabaseConfig.useDatabaseFile(directory.resolve(UUID.randomUUID() + ".db"));
        DatabaseConfig.initialize();
    }

    Clock clock(int day) {
        return Clock.fixed(T.plus(Duration.ofDays(day)), ZoneOffset.UTC);
    }

    AdaptiveQuestService service(int day) {
        return new AdaptiveQuestService(
                clock(day), assets, () -> true, new ProblemLibraryService());
    }

    ChallengeSpec spec(String id) {
        return JavaTrainingPack.challenges().stream()
                .filter(s -> s.ref().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    ChallengePresentationService presentations(int day) {
        return new ChallengePresentationService(new QuestCatalogRepository(), assets, clock(day));
    }

    void goal(AdaptiveQuestService service, SkillRef skill) {
        service.respond(
                new LearnerCommand(
                        "goal-" + UUID.randomUUID(),
                        CommandKind.CHANGE_GOAL,
                        null,
                        null,
                        skill,
                        null,
                        null,
                        null));
    }

    void budget(AdaptiveQuestService service, int minutes) {
        service.respond(
                new LearnerCommand(
                        "budget-" + UUID.randomUUID(),
                        CommandKind.CHANGE_BUDGET,
                        null,
                        null,
                        null,
                        minutes,
                        null,
                        null));
    }

    LearnerCommand choose(String id, CommandKind kind, Decision decision, String challenge) {
        return new LearnerCommand(
                id, kind, decision.id(), new CatalogRef(challenge, 1), null, null, null, null);
    }

    LearnerCommand choose(String id, CommandKind kind, Decision decision) {
        return choose(id, kind, decision, decision.result().primary().challenge().id());
    }

    SubmissionRequest request(String id, int day, AssetKind kind) {
        var s = spec(id);
        var q =
                JavaTrainingPack.quests().stream()
                        .filter(x -> x.challenges().contains(s.ref()))
                        .findFirst()
                        .orElseThrow();
        String presentation = "real-" + UUID.randomUUID();
        presentations(day)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                q.ref(),
                                s.ref(),
                                s.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        return new SubmissionRequest(
                "command-" + UUID.randomUUID(),
                presentation,
                assets.read(
                        s.assets().stream()
                                .filter(a -> a.kind() == kind)
                                .findFirst()
                                .orElseThrow()),
                EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                "");
    }

    SubmissionRequest pass(String id, int day) {
        var r = request(id, day, AssetKind.REFERENCE);
        assertEquals(
                EvaluationTypes.Outcome.PASSED,
                EvaluationTestFixtures.service(clock(day)).submit(r).outcome());
        return r;
    }

    long count(String table) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    Branch intended(Decision d) {
        return switch (d.result().reasons().getFirst().code()) {
            case UNKNOWN_BASELINE -> Branch.BASELINE;
            case NEED_DIVERSITY -> Branch.DIVERSITY;
            case TRANSFER_READY -> Branch.TRANSFER;
            case REPEATED_FAILURE -> Branch.REPEATED_FAILURE;
            case SINGLE_FAILURE -> Branch.SINGLE_FAILURE;
            case ASSISTED_SUCCESS -> Branch.ASSISTED;
            case UNCERTAIN_SUPPORT -> Branch.UNCERTAIN;
            case RETENTION_DUE -> Branch.RETENTION;
            default -> d.result().branch();
        };
    }

    @Test
    void freshDecisionHasFourExactRevisionsAndNeverExposesCandidate() throws Exception {
        var d = service(0).recommend(T);
        assertEquals(Branch.BASELINE, d.result().branch());
        assertEquals(4, d.input().skills().size());
        assertEquals(14, d.input().candidates().size());
        assertEquals(0, count("evolution_challenge_presentations"));
        assertEquals(0, count("evolution_submissions"));
        assertEquals(0, count("evolution_evidence_observations"));
        for (var skill : d.input().skills()) {
            var revision = new EstimateRevisionRepository().find(skill.revision()).orElseThrow();
            assertEquals(T, revision.estimate().asOf());
            assertEquals(JavaDebuggingSkillSeed.GRAPH, revision.estimate().graph());
            assertEquals(skill.inputSha(), revision.inputSha());
        }
    }

    @Test
    void sameInputAndConcurrentRecomputeConvergeAcrossRestart() throws Exception {
        var first = service(0).recommend(T);
        long estimates = count("evolution_estimate_revisions");
        assertEquals(first, service(0).recommend(T));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service(0).recommend(T));
            var b = pool.submit(() -> service(0).recommend(T));
            assertEquals(first, a.get(20, TimeUnit.SECONDS));
            assertEquals(first, b.get(20, TimeUnit.SECONDS));
        }
        DatabaseConfig.initialize();
        assertEquals(first, new AdaptationDecisionRepository().find(first.id()).orElseThrow());
        assertEquals(1, count("evolution_adaptation_decisions"));
        assertEquals(estimates, count("evolution_estimate_revisions"));
    }

    @Test
    void acceptDoesNotPresentAndStartRecordsExactlyOnceAfterLostResponse() throws Exception {
        var service = service(0);
        var d = service.recommend(T);
        var command = choose("accept-baseline", CommandKind.ACCEPT, d);
        var choice = service.respond(command);
        assertEquals(choice, service.respond(command));
        assertEquals(0, count("evolution_challenge_presentations"));
        var view = service.start(command.id(), Familiarity.DECLARED_UNFAMILIAR);
        assertFalse(view.prompt().isBlank());
        DatabaseConfig.initialize();
        assertEquals(view, service(0).start(command.id(), Familiarity.DECLARED_UNFAMILIAR));
        assertEquals(1, count("evolution_challenge_presentations"));
        assertEquals(0, count("evolution_evidence_observations"));
        assertEquals(
                BaselineDiagnosticService.presentationId(view.receipt().challenge()),
                view.receipt().id());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.start(command.id(), Familiarity.DECLARED_FAMILIAR));
    }

    @Test
    void pauseDeclineDeferAndResumeAreDurableAndNeverNegativeEvidence() throws Exception {
        var service = service(0);
        var first = service.recommend(T);
        String id = first.result().primary().challenge().id();
        var decline = choose("decline", CommandKind.DECLINE, first);
        var choice = service.respond(decline);
        assertEquals(choice, service.respond(decline));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.respond(
                                new LearnerCommand(
                                        "decline",
                                        CommandKind.PAUSE,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        var after = service.recommend(T);
        assertNotEquals(first.id(), after.id());
        assertNotEquals(id, after.result().primary().challenge().id());
        service.respond(LearnerCommand.simple("pause", CommandKind.PAUSE));
        assertEquals(Branch.PAUSED, service.recommend(T).result().branch());
        DatabaseConfig.initialize();
        assertEquals(Branch.PAUSED, service(0).recommend(T).result().branch());
        service.respond(LearnerCommand.simple("resume", CommandKind.RESUME));
        service.respond(
                new LearnerCommand(
                        "reverse-decline",
                        CommandKind.RESUME_CANDIDATE,
                        null,
                        new CatalogRef(id, 1),
                        null,
                        null,
                        null,
                        null));
        assertEquals(id, service.recommend(T).result().primary().challenge().id());
        var now = service.recommend(T);
        service.respond(
                new LearnerCommand(
                        "defer",
                        CommandKind.DEFER,
                        now.id(),
                        now.result().primary().challenge(),
                        null,
                        null,
                        null,
                        T.plusSeconds(86400)));
        assertNotEquals(id, service.recommend(T).result().primary().challenge().id());
        assertEquals(
                id, service(1).recommend(T.plusSeconds(86400)).result().primary().challenge().id());
        assertEquals(0, count("evolution_evidence_observations"));
        assertEquals(0, count("problem_attempts"));
    }

    @Test
    void chooseAlternativeAndExploreReuseExistingWorkspaceWithoutHoldOutEscape() throws Exception {
        var service = service(0);
        budget(service, 60);
        var d = service.recommend(T);
        var alternative =
                d.result().alternatives().stream()
                        .filter(s -> !s.exploratory())
                        .findFirst()
                        .orElseThrow();
        var choose =
                choose(
                        "choose-alternative",
                        CommandKind.CHOOSE_ALTERNATIVE,
                        d,
                        alternative.challenge().id());
        service.respond(choose);
        assertEquals(
                alternative.challenge(),
                service.start(choose.id(), Familiarity.UNKNOWN).receipt().challenge());
        var current = service.recommend(T);
        var explore = choose("explore-counter", CommandKind.EXPLORE, current, "counter-repair");
        service.respond(explore);
        assertEquals(0, count("java_solution_drafts"));
        var view = service.start(explore.id(), Familiarity.DECLARED_UNFAMILIAR);
        assertNotNull(view.draft());
        assertEquals(1, count("java_solution_drafts"));
        assertEquals(1, count("problem_solving_sessions"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.respond(
                                choose(
                                        "leak-transfer",
                                        CommandKind.EXPLORE,
                                        current,
                                        "inventory-transfer")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.respond(
                                choose("leak-post", CommandKind.EXPLORE, current, "post-ledger")));
    }

    @Test
    void staleAcceptedChoiceCannotOpenReservedTaskOrBypassPauseAndDecline() {
        var service = service(0);
        var d = service.recommend(T);
        var command = choose("accepted", CommandKind.ACCEPT, d);
        service.respond(command);
        service.respond(LearnerCommand.simple("pause", CommandKind.PAUSE));
        assertThrows(
                IllegalStateException.class,
                () -> service.start(command.id(), Familiarity.UNKNOWN));
        service.respond(LearnerCommand.simple("resume", CommandKind.RESUME));
        service.respond(choose("hide", CommandKind.DECLINE, d));
        assertThrows(
                IllegalStateException.class,
                () -> service.start(command.id(), Familiarity.UNKNOWN));
    }

    @Test
    void previouslyExposedAlternateRouteInvalidatesNovelDecisionButOldDecisionSurvives() {
        var service = service(0);
        var d = service.recommend(T);
        var command = choose("accepted", CommandKind.ACCEPT, d);
        service.respond(command);
        var candidate =
                d.input().candidates().stream()
                        .filter(c -> c.challenge().equals(command.challenge()))
                        .findFirst()
                        .orElseThrow();
        presentations(0)
                .present(
                        new ChallengePresentation.Request(
                                "alternate-route",
                                candidate.quest(),
                                candidate.challenge(),
                                candidate.cohort(),
                                Familiarity.UNKNOWN));
        assertThrows(
                IllegalStateException.class,
                () -> service.start(command.id(), Familiarity.UNKNOWN));
        assertEquals(d, new AdaptationDecisionRepository().find(d.id()).orElseThrow());
        assertEquals(
                0,
                new AdaptationDecisionRepository()
                        .find(d.id()).orElseThrow().input().candidates().stream()
                                .filter(c -> c.challenge().equals(command.challenge()))
                                .findFirst()
                                .orElseThrow()
                                .familyPresentations());
    }

    @Test
    void genuineEvaluatedFamiliesAndReplayDoNotBypassPrerequisites() throws Exception {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var first = pass("counter-repair", 0);
        var d = service.recommend(T);
        assertEquals(Branch.DIVERSITY, intended(d));
        assertEquals(
                1,
                d.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow()
                        .independentFamilies());
        long evidence = count("evolution_evidence_observations");
        EvaluationTestFixtures.service(clock(0)).submit(first);
        assertEquals(d, service.recommend(T));
        assertEquals(evidence, count("evolution_evidence_observations"));
        pass("atomic-boundary-drill", 1);
        var after = service(1).recommend(clock(1).instant());
        assertEquals(Branch.TRANSFER, intended(after));
        assertEquals(
                2,
                after.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow()
                        .independentFamilies());
        assertNull(
                after.result().primary(),
                "Missing independent reproduction support must remain explicit");
        assertTrue(
                after.result().candidates().stream()
                        .anyMatch(r -> r.exclusions().contains(Reason.PREREQUISITE)));
    }

    @Test
    void assistedRealEvaluationAndKnownRevealCannotAdvanceDifficulty() {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var request = request("counter-repair", 0, AssetKind.REFERENCE);
        presentations(0).revealSolution(request.presentationId(), "revealed");
        assertEquals(
                EvaluationTypes.Outcome.PASSED,
                EvaluationTestFixtures.service(clock(0)).submit(request).outcome());
        var d = service.recommend(T);
        assertEquals(Branch.ASSISTED, intended(d));
        var skill =
                d.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, skill.independentFamilies());
        assertTrue(
                d.result().candidates().stream()
                        .filter(r -> r.eligible())
                        .allMatch(
                                r ->
                                        d.input().candidates().stream()
                                                        .filter(
                                                                c ->
                                                                        c.key()
                                                                                .equals(
                                                                                        r
                                                                                                .candidateKey()))
                                                        .findFirst()
                                                        .orElseThrow()
                                                        .difficulty()
                                                <= 2));
    }

    @Test
    void realFailuresAndCommandReplayAreDifferentDeliberateOpportunities() {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var first = request("atomic-boundary-drill", 0, AssetKind.MUTANT);
        var engine = EvaluationTestFixtures.service(clock(0));
        engine.submit(first);
        var one = service.recommend(T);
        assertEquals(Branch.SINGLE_FAILURE, intended(one));
        engine.submit(first);
        assertEquals(one, service.recommend(T));
        var second = request("atomic-boundary-drill", 1, AssetKind.MUTANT);
        EvaluationTestFixtures.service(clock(1)).submit(second);
        var two = service(1).recommend(clock(1).instant());
        assertEquals(Branch.REPEATED_FAILURE, intended(two));
        assertTrue(
                two.result().reasons().stream()
                        .anyMatch(r -> r.code() == Reason.REST_OR_CHANGE_DIRECTION));
    }

    @Test
    void unavailableEvaluatorResultDoesNotBecomeFailure() {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var request = request("counter-repair", 0, AssetKind.REFERENCE);
        var unsupported =
                new SubmissionRequest(
                        request.id(),
                        request.presentationId(),
                        "class Exercise { private final java.util.concurrent.locks.ReentrantLock"
                            + " lock = new java.util.concurrent.locks.ReentrantLock(); private long"
                            + " total; public void add(long n){ lock.lock(); try{if(n<0)throw new"
                            + " IllegalArgumentException();total+=n;}finally{lock.unlock();}}"
                            + " public long value(){lock.lock();try{return"
                            + " total;}finally{lock.unlock();}} }",
                        request.outsideAssistance(),
                        "");
        var outcome = EvaluationTestFixtures.service(clock(0)).submit(unsupported).outcome();
        assertEquals(EvaluationTypes.Outcome.UNSUPPORTED, outcome);
        var d = service.recommend(T);
        assertEquals(Branch.BASELINE, intended(d));
        assertTrue(
                d.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow()
                        .observations()
                        .isEmpty());
    }

    @Test
    void correctionBetweenReadAndPublicationCannotPublishOldPositive() {
        var request = pass("counter-repair", 0);
        var normal = service(1);
        goal(normal, ATOMIC);
        budget(normal, 60);
        var once = new AtomicBoolean();
        var service =
                new AdaptiveQuestService(
                        clock(1),
                        assets,
                        () -> true,
                        new ProblemLibraryService(),
                        () -> {
                            if (once.compareAndSet(false, true))
                                EvaluationTestFixtures.service(clock(1))
                                        .invalidate(request.id(), "concurrent-withdrawal", true);
                        });
        var d = service.recommend(clock(1).instant());
        assertEquals(Branch.BASELINE, intended(d));
        assertTrue(
                d.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow()
                        .observations()
                        .isEmpty());
    }

    @Test
    void historicalDecisionSurvivesEvidenceWithdrawalMappingChangeAndReset() throws Exception {
        var first = pass("counter-repair", 0);
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var original = service.recommend(T);
        EvaluationTestFixtures.service(clock(1)).invalidate(first.id(), "withdraw", true);
        new EstimateRevisionRepository().reset(JavaDebuggingSkillSeed.GRAPH, "new-epoch", clock(1));
        var old = DurableEvaluationRepository.mapping(spec("counter-repair"));
        new EvidenceMappingRepository()
                .publish(
                        new com.codefit.evolution.evidence.EvidenceMapping(
                                new com.codefit.evolution.evidence.EvidenceMappingRef(
                                        old.ref().mappingId(), 2),
                                old.graph(),
                                old.rules()));
        DatabaseConfig.initialize();
        assertEquals(
                original, new AdaptationDecisionRepository().find(original.id()).orElseThrow());
        var now = service(2).recommend(clock(2).instant());
        assertNotEquals(original.id(), now.id());
        assertEquals(Branch.BASELINE, intended(now));
        assertTrue(now.input().skills().stream().allMatch(s -> s.epoch().equals("new-epoch")));
        assertEquals(ATOMIC, now.input().preferences().goal());
        assertEquals(60, now.input().preferences().budgetMinutes());
        assertEquals(1, count("evolution_challenge_presentations"));
    }

    @Test
    void realRehearsalDefersRetentionAndAllHeldOutContentRemainsUnpresented() throws Exception {
        pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        pass("inventory-transfer", 2);
        var service = service(2);
        goal(service, ATOMIC);
        budget(service, 60);
        var early = service.recommend(clock(2).instant());
        assertEquals(Branch.SUPPORTED, intended(early));
        var retention =
                early.input().candidates().stream()
                        .filter(c -> c.cohort() == Cohort.RETENTION)
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, retention.familyPresentations());
        assertTrue(
                early.result().candidates().stream()
                        .anyMatch(r -> r.exclusions().contains(Reason.RETENTION_NOT_DUE)));
        var due = service(9).recommend(clock(9).instant());
        assertFalse(
                due.result().candidates().stream()
                        .filter(r -> r.candidateKey().equals(retention.key()))
                        .findFirst()
                        .orElseThrow()
                        .exclusions()
                        .contains(Reason.RETENTION_NOT_DUE));
        var rehearsal = request("counter-repair-b", 8, AssetKind.REFERENCE);
        EvaluationTestFixtures.service(clock(8)).prepare(rehearsal);
        var delayed = service(9).recommend(clock(9).instant());
        assertTrue(
                delayed.result().candidates().stream()
                        .filter(r -> r.candidateKey().equals(retention.key()))
                        .findFirst()
                        .orElseThrow()
                        .exclusions()
                        .contains(Reason.RETENTION_NOT_DUE));
        assertEquals(4, count("evolution_challenge_presentations"));
    }

    @Test
    void unavailableAssetsRunnerAndFutureGraphFailClosedWithoutBreakingOrdinaryStudy() {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var noRunner =
                new AdaptiveQuestService(
                        clock(0), assets, () -> false, new ProblemLibraryService());
        assertNull(noRunner.recommend(T).result().primary());
        var missing =
                new BundledAssets() {
                    @Override
                    public void validate(ChallengeSpec spec) {
                        throw new IllegalStateException("Missing installed assets");
                    }
                };
        var noAssets =
                new AdaptiveQuestService(
                        clock(0), missing, () -> true, new ProblemLibraryService());
        assertEquals(Branch.UNAVAILABLE, noAssets.recommend(T).result().branch());
        assertEquals(
                Branch.UNAVAILABLE,
                service.recommend(new GraphRef(JavaDebuggingSkillSeed.GRAPH.graphId(), 2), T)
                        .result()
                        .branch());
        assertDoesNotThrow(() -> new ProblemLibraryService().getNextRecommendedProblem());
    }

    @Test
    void skippedDiagnosticsAndBudgetLimitReturnHonestEmptyChoice() throws Exception {
        var baseline =
                new BaselineDiagnosticService(
                        presentations(0), EvaluationTestFixtures.service(clock(0)), clock(0));
        baseline.skipAll("skip-all");
        var service = service(0);
        budget(service, 5);
        var d = service.recommend(T);
        assertNull(d.result().primary());
        assertEquals(Branch.DEPLETED, d.result().branch());
        assertTrue(
                d.result().candidates().stream()
                        .anyMatch(r -> r.exclusions().contains(Reason.BASELINE_SKIPPED)));
        assertTrue(
                d.result().candidates().stream()
                        .anyMatch(r -> r.exclusions().contains(Reason.BUDGET)));
        assertEquals(0, count("evolution_challenge_presentations"));
        budget(service, 60);
        assertEquals(Action.INTRODUCTION, service.recommend(T).result().primary().action());
    }

    @Test
    void curriculumTrackDelegatesExactlyAndKeepsImportedFrontier() {
        new TrainingSheetImportService()
                .importWorkbook(
                        Path.of("data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var library =
                new ProblemLibraryService() {
                    @Override
                    public Optional<ProblemLibraryEntry> getNextRecommendedProblem() {
                        calls.incrementAndGet();
                        return super.getNextRecommendedProblem();
                    }
                };
        var expected =
                new ProblemLibraryService()
                        .getNextRecommendedProblem()
                        .map(e -> e.problem().getId())
                        .orElse(null);
        var service = new AdaptiveQuestService(clock(0), assets, () -> true, library);
        service.respond(
                new LearnerCommand(
                        "switch-curriculum",
                        CommandKind.SWITCH_TRACK,
                        null,
                        null,
                        null,
                        null,
                        Track.CURRICULUM,
                        null));
        var d = service.recommend(T);
        assertEquals(1, calls.get());
        assertEquals(expected, d.input().curriculumProblem());
        assertEquals(Branch.CURRICULUM, d.result().branch());
        assertNotNull(expected);
        service.respond(
                new LearnerCommand(
                        "accept-curriculum",
                        CommandKind.ACCEPT,
                        d.id(),
                        null,
                        null,
                        null,
                        null,
                        null));
        service.respond(
                new LearnerCommand(
                        "switch-evolution",
                        CommandKind.SWITCH_TRACK,
                        null,
                        null,
                        null,
                        null,
                        Track.EVOLUTION,
                        null));
        assertEquals(Branch.BASELINE, service.recommend(T).result().branch());
        assertEquals(
                expected,
                new ProblemLibraryService()
                        .getNextRecommendedProblem()
                        .map(e -> e.problem().getId())
                        .orElse(null));
    }

    @Test
    void storedMetadataContainsNoPrivateArtifactOrReservedPrompt() throws Exception {
        var d = service(0).recommend(T);
        service(0).respond(choose("accept", CommandKind.ACCEPT, d));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT inputs FROM evolution_adaptation_decisions")) {
            while (r.next()) {
                String raw =
                        new String(r.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("class Exercise"));
                assertFalse(raw.contains("public class Main"));
                for (var spec : JavaTrainingPack.challenges()) {
                    String prompt =
                            assets.read(
                                    spec.assets().stream()
                                            .filter(a -> a.kind() == AssetKind.PROMPT)
                                            .findFirst()
                                            .orElseThrow());
                    assertFalse(raw.contains(prompt));
                }
            }
        }
        assertEquals(1, new AdaptationDecisionRepository().choices(0, 128).size());
        assertFalse(new AdaptationDecisionRepository().history(0, 128).isEmpty());
    }

    @Test
    void concurrentIdenticalResponsesCommitExactlyOneChoice() throws Exception {
        var service = service(0);
        var decision = service.recommend(T);
        var command = choose("simultaneous", CommandKind.ACCEPT, decision);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.respond(command));
            var b = pool.submit(() -> service(0).respond(command));
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("evolution_adaptation_choices"));
        assertEquals(0, count("evolution_challenge_presentations"));
    }

    @Test
    void deferPayloadConflictAndBackwardClockAreRejectedWithoutChangingPreferences() {
        var service = service(1);
        var decision = service.recommend(clock(1).instant());
        var command =
                new LearnerCommand(
                        "defer-identity",
                        CommandKind.DEFER,
                        decision.id(),
                        decision.result().primary().challenge(),
                        null,
                        null,
                        null,
                        clock(3).instant());
        var first = service.respond(command);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.respond(
                                new LearnerCommand(
                                        command.id(),
                                        command.kind(),
                                        command.decisionId(),
                                        command.challenge(),
                                        null,
                                        null,
                                        null,
                                        clock(4).instant())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service(0)
                                .respond(
                                        LearnerCommand.simple(
                                                "clock-backwards", CommandKind.PAUSE)));
        assertEquals(first, new AdaptationDecisionRepository().choice(command.id()).orElseThrow());
    }

    @Test
    void estimateResetDoesNotClearPauseDeclineOrExposure() throws Exception {
        var service = service(0);
        var d = service.recommend(T);
        service.respond(choose("decline-persist", CommandKind.DECLINE, d));
        service.respond(LearnerCommand.simple("pause-persist", CommandKind.PAUSE));
        new EstimateRevisionRepository().reset(JavaDebuggingSkillSeed.GRAPH, "reset", clock(1));
        DatabaseConfig.initialize();
        var now = service(2).recommend(clock(2).instant());
        assertEquals(Branch.PAUSED, now.result().branch());
        assertTrue(
                now.input()
                        .preferences()
                        .declined()
                        .contains(d.result().primary().challenge().id()));
        assertEquals(0, count("evolution_challenge_presentations"));
    }

    @Test
    void concurrentStartOfSameAcceptedCommandPresentsExactlyOnce() throws Exception {
        var service = service(0);
        var decision = service.recommend(T);
        var command = choose("parallel-start", CommandKind.ACCEPT, decision);
        service.respond(command);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.start(command.id(), Familiarity.UNKNOWN));
            var b = pool.submit(() -> service(0).start(command.id(), Familiarity.UNKNOWN));
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("evolution_challenge_presentations"));
        assertEquals(0, count("evolution_evidence_observations"));
    }

    @Test
    void realStaleAndContradictoryEstimatesCannotUnlockAdvancedWork() {
        pass("counter-repair", 0);
        pass("atomic-boundary-drill", 1);
        var learner = service(1);
        goal(learner, ATOMIC);
        budget(learner, 60);
        var stale = service(32).recommend(clock(32).instant());
        assertEquals(Branch.UNCERTAIN, intended(stale));
        var failure = request("remaining-capacity", 2, AssetKind.MUTANT);
        EvaluationTestFixtures.service(clock(2)).submit(failure);
        var contradictory = service(2).recommend(clock(2).instant());
        assertEquals(Branch.SINGLE_FAILURE, intended(contradictory));
        assertTrue(
                contradictory.input().skills().stream()
                        .filter(s -> s.skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow()
                        .contradicted());
        assertTrue(
                contradictory.result().candidates().stream()
                        .filter(r -> r.eligible())
                        .noneMatch(
                                r ->
                                        contradictory.input().candidates().stream()
                                                .filter(c -> c.key().equals(r.candidateKey()))
                                                .anyMatch(c -> c.cohort() == Cohort.TRANSFER)));
    }

    @Test
    void acceptedChoiceCannotOpenMissingExactAssetsAndDoesNotExposeAnything() throws Exception {
        var learner = service(0);
        var decision = learner.recommend(T);
        var command = choose("before-missing", CommandKind.ACCEPT, decision);
        learner.respond(command);
        var missing =
                new BundledAssets() {
                    @Override
                    public void validate(ChallengeSpec spec) {
                        throw new IllegalStateException("Unavailable exact content");
                    }
                };
        var after =
                new AdaptiveQuestService(
                        clock(0), missing, () -> true, new ProblemLibraryService());
        assertThrows(
                IllegalStateException.class, () -> after.start(command.id(), Familiarity.UNKNOWN));
        assertEquals(0, count("evolution_challenge_presentations"));
        assertEquals(
                decision, new AdaptationDecisionRepository().find(decision.id()).orElseThrow());
    }

    @Test
    void decisionMetadataTamperingAndMissingHistoricalPolicyFailClosed() throws Exception {
        var first = service(0).recommend(T);
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            // Simulate corrupt storage only in an isolated fixture; production guards are tested
            // separately.
            s.execute("DROP TRIGGER evolution_adaptation_decisions_immutable_update");
            s.execute("UPDATE evolution_adaptation_decisions SET input_sha='invalid'");
        }
        assertThrows(
                IllegalStateException.class,
                () -> new AdaptationDecisionRepository().find(first.id()));
        var unknownInput =
                new AdaptationInput(
                        first.input().graph(),
                        first.input().graphAvailable(),
                        first.input().limitation(),
                        first.input().preferences(),
                        T,
                        "adaptive-future",
                        first.input().configuration(),
                        first.input().skills(),
                        first.input().candidates(),
                        null);
        byte[] bytes = AdaptationCodec.encode(unknownInput);
        String sha = CatalogCodec.digest(bytes);
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "INSERT INTO"
                                    + " evolution_adaptation_decisions(id,input_sha,policy,configuration,graph_id,graph_version,preferences_version,as_of,inputs,output_sha,outputs)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, "decision-" + sha);
            s.setString(2, sha);
            s.setString(3, unknownInput.policy());
            s.setString(4, unknownInput.configuration());
            s.setString(5, unknownInput.graph().graphId());
            s.setInt(6, 1);
            s.setLong(7, unknownInput.preferences().version());
            s.setString(8, T.toString());
            s.setBytes(9, bytes);
            byte[] output = AdaptationCodec.encode(first.result());
            s.setString(10, CatalogCodec.digest(output));
            s.setBytes(11, output);
            s.executeUpdate();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdaptationDecisionRepository().find("decision-" + sha));
    }

    @Test
    void changedGoalAndHistoricalAsOfNeverInferFuturePerformance() {
        var service = service(0);
        goal(service, ATOMIC);
        budget(service, 60);
        var before = service.recommend(T);
        assertEquals(ATOMIC, before.input().preferences().goal());
        pass("counter-repair", 1);
        var historical = service(2).recommend(T);
        assertEquals(Branch.BASELINE, intended(historical));
        assertEquals(before, new AdaptationDecisionRepository().find(before.id()).orElseThrow());
        var liveness = new SkillRef("java.concurrency.liveness", 1);
        goal(service(2), liveness);
        var next = service(2).recommend(clock(2).instant());
        assertEquals(liveness, next.input().preferences().goal());
        assertTrue(
                next.input().skills().stream()
                        .filter(s -> s.skill().equals(liveness))
                        .findFirst()
                        .orElseThrow()
                        .observations()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> goal(service(2), new SkillRef("java.concurrency.liveness", 2)));
    }

    @Test
    void realAssistedHarderSuccessDoesNotRaiseConsolidationDifficulty() {
        pass("counter-repair", 0);
        pass("atomic-boundary-drill", 1);
        var learner = service(1);
        goal(learner, ATOMIC);
        budget(learner, 60);
        var request = request("remaining-capacity", 2, AssetKind.REFERENCE);
        presentations(2).revealHint(request.presentationId(), "capacity-guidance", 1);
        assertEquals(
                EvaluationTypes.Outcome.PASSED,
                EvaluationTestFixtures.service(clock(2)).submit(request).outcome());
        var decision = service(2).recommend(clock(2).instant());
        assertEquals(Branch.ASSISTED, intended(decision));
        var target =
                decision.input().candidates().stream()
                        .filter(c -> c.challenge().id().equals("distinct-id-accumulator"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(3, target.difficulty());
        assertTrue(
                decision.result().candidates().stream()
                        .filter(r -> r.candidateKey().equals(target.key()))
                        .findFirst()
                        .orElseThrow()
                        .exclusions()
                        .contains(Reason.DIFFICULTY));
    }
}
