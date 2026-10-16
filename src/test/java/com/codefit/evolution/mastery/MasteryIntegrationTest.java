package com.codefit.evolution.mastery;

import static com.codefit.evolution.mastery.MasteryTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evidence.*;
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

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class MasteryIntegrationTest {
    static final Instant T = Instant.parse("2026-06-12T12:00:00Z");
    static final SkillRef ATOMIC = new SkillRef("java.concurrency.atomic-update", 1);
    @TempDir Path directory;
    private final BundledAssets assets = new BundledAssets();

    @BeforeEach
    void freshDb() {
        DatabaseConfig.useDatabaseFile(directory.resolve(UUID.randomUUID() + ".db"));
        DatabaseConfig.initialize();
    }

    static Clock clock(int day) {
        return Clock.fixed(T.plus(Duration.ofDays(day)), ZoneOffset.UTC);
    }

    ChallengePresentationService presentations(int day) {
        return new ChallengePresentationService(new QuestCatalogRepository(), assets, clock(day));
    }

    ChallengeSpec spec(String id) {
        return JavaTrainingPack.challenges().stream()
                .filter(s -> s.ref().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    SubmissionRequest request(
            String id, int day, AssetKind kind, EvaluationTypes.OutsideAssistance aid) {
        var spec = spec(id);
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        String presentation = "presentation-" + UUID.randomUUID();
        presentations(day)
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                spec.ref(),
                                spec.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String artifact =
                assets.read(
                        spec.assets().stream()
                                .filter(a -> a.kind() == kind)
                                .findFirst()
                                .orElseThrow());
        return new SubmissionRequest(
                "command-" + UUID.randomUUID(), presentation, artifact, aid, "");
    }

    SubmissionRequest pass(String id, int day) {
        var r =
                request(
                        id,
                        day,
                        AssetKind.REFERENCE,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED);
        assertEquals(
                EvaluationTypes.Outcome.PASSED,
                EvaluationTestFixtures.service(clock(day)).submit(r).outcome());
        return r;
    }

    EstimateRevisionRepository.Revision atomic(int day) {
        return new MasteryEstimateService()
                .pilot(clock(day).instant()).stream()
                        .filter(r -> r.estimate().skill().equals(ATOMIC))
                        .findFirst()
                        .orElseThrow();
    }

    long count(String table) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void freshAllFourSkillsAndSkippedBaselineStayUnknownAcrossRestart() throws Exception {
        var baseline = new BaselineDiagnosticService();
        baseline.skipAll("skip-all");
        baseline.skipAll("skip-all");
        var status = baseline.status(Instant.now());
        assertTrue(status.skipped());
        assertEquals(3, status.diagnostics().size());
        assertEquals(4, status.estimates().size());
        assertTrue(status.estimates().stream().allMatch(r -> r.estimate().band() == Band.UNKNOWN));
        assertEquals(0, count("evolution_challenge_presentations"));
        assertEquals(0, count("evolution_submissions"));
        assertEquals(0, count("evolution_evidence_observations"));
        DatabaseConfig.initialize();
        assertTrue(new BaselineDiagnosticService().status(Instant.now()).skipped());
        assertThrows(
                IllegalStateException.class,
                () ->
                        baseline.present(
                                new CatalogRef("baseline-counter", 1),
                                Familiarity.DECLARED_UNFAMILIAR));
        baseline.resumeAll("resume-all");
        assertFalse(baseline.status(Instant.now()).skipped());
    }

    @Test
    void realBaselineReentryUsesExistingReceiptAndEvaluator() throws Exception {
        var baseline =
                new BaselineDiagnosticService(
                        presentations(0), EvaluationTestFixtures.service(clock(0)), clock(0));
        var ref = new CatalogRef("baseline-counter", 1);
        var first = baseline.present(ref, Familiarity.DECLARED_UNFAMILIAR);
        assertEquals(
                first.receipt(), baseline.present(ref, Familiarity.DECLARED_UNFAMILIAR).receipt());
        var challenge = spec(ref.id());
        String source =
                assets.read(
                        challenge.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "baseline-submit",
                        first.receipt().id(),
                        source,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                        "");
        assertEquals(EvaluationTypes.Outcome.PASSED, baseline.submit(ref, request).outcome());
        assertEquals(baseline.submissionStatus(request.id()), baseline.submit(ref, request));
        baseline.skipTask(ref, "skip-counter");
        assertEquals(first.receipt(), baseline.resumePresentation(ref).receipt());
        assertThrows(
                IllegalStateException.class,
                () ->
                        baseline.submit(
                                ref,
                                new SubmissionRequest(
                                        "paused-new-command",
                                        first.receipt().id(),
                                        source,
                                        EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                                        "")));
        DatabaseConfig.initialize();
        var status = baseline.status(clock(1).instant());
        var task =
                status.diagnostics().stream()
                        .filter(d -> d.challenge().equals(ref))
                        .findFirst()
                        .orElseThrow();
        assertTrue(task.skipped());
        assertEquals(List.of(request.id()), task.submissions());
        assertEquals(1, count("evolution_challenge_presentations"));
        assertEquals(1, count("evolution_submissions"));
        assertEquals(Band.DEVELOPING, atomic(1).estimate().band());
        assertEquals(
                baseline.submissionStatus(request.id()), baseline.resumeSubmission(request.id()));
    }

    @Test
    void realIndependentFamiliesTransferRetentionAndReplaysRemainTruthful() throws Exception {
        var first = pass("counter-repair", 0);
        var coordinator = EvaluationTestFixtures.service(clock(0));
        coordinator.submit(first);
        assertEquals(Band.DEVELOPING, atomic(0).estimate().band());
        pass("remaining-capacity", 1);
        assertEquals(Band.INDEPENDENT, atomic(1).estimate().band());
        pass("inventory-transfer", 2);
        assertEquals(Band.TRANSFER_SUPPORTED, atomic(2).estimate().band());
        pass("queue-retention", 9);
        var e = atomic(9).estimate();
        assertEquals(Band.RETENTION_SUPPORTED, e.band());
        assertEquals(4, e.independentFamilies());
        assertEquals(4, e.contributions().size());
        assertEquals(4, count("problem_attempts"));
        assertEquals(4, count("evolution_evidence_observations"));
        var stale = atomic(40).estimate();
        assertEquals(Band.RETENTION_SUPPORTED, stale.band());
        assertEquals(Freshness.STALE, stale.freshness());
        assertFalse(stale.currentCapabilitySupported());
    }

    @Test
    void relatedVariantsAndExplicitHintsCannotIncreaseIndependentFamilies() {
        pass("counter-repair", 0);
        pass("counter-repair-b", 1);
        var r =
                request(
                        "remaining-capacity",
                        2,
                        AssetKind.REFERENCE,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED);
        presentations(2).revealHint(r.presentationId(), "hint-before-submit", 1);
        assertEquals(
                EvaluationTypes.Outcome.PASSED,
                EvaluationTestFixtures.service(clock(2)).submit(r).outcome());
        var e = atomic(3).estimate();
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(1, e.independentFamilies());
        assertTrue(e.reasons().stream().anyMatch(reason -> reason.code() == ReasonCode.ASSISTED));
    }

    @Test
    void revealAndUnknownAssistanceAreNeverIndependent() {
        var r =
                request(
                        "counter-repair",
                        0,
                        AssetKind.REFERENCE,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED);
        presentations(0).revealSolution(r.presentationId(), "solution");
        EvaluationTestFixtures.service(clock(0)).submit(r);
        var unknown =
                request(
                        "remaining-capacity",
                        1,
                        AssetKind.REFERENCE,
                        EvaluationTypes.OutsideAssistance.UNKNOWN);
        EvaluationTestFixtures.service(clock(1)).submit(unknown);
        assertEquals(0, atomic(2).estimate().independentFamilies());
    }

    @Test
    void exposedTransferDoesNotBecomeUnseenThroughAnotherPresentation() {
        pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        request(
                "inventory-transfer",
                2,
                AssetKind.REFERENCE,
                EvaluationTypes.OutsideAssistance.NONE_DECLARED);
        pass("inventory-transfer", 3);
        assertEquals(Band.INDEPENDENT, atomic(3).estimate().band());
    }

    @Test
    void rehearsalEvenWithoutSuccessfulEvaluationInvalidatesRetention() {
        pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        pass("inventory-transfer", 2);
        var rehearsal =
                request(
                        "counter-repair-b",
                        8,
                        AssetKind.MUTANT,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED);
        EvaluationTestFixtures.service(clock(8)).prepare(rehearsal);
        pass("queue-retention", 9);
        assertEquals(Band.TRANSFER_SUPPORTED, atomic(9).estimate().band());
    }

    @Test
    void withdrawalReestimatesWithoutRegressionAndSavedRevisionRemainsInspectable() {
        var first = pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        var before = atomic(1);
        assertEquals(Band.INDEPENDENT, before.estimate().band());
        EvaluationTestFixtures.service(clock(2)).invalidate(first.id(), "withdraw-first", true);
        var after = atomic(2);
        assertEquals(Band.DEVELOPING, after.estimate().band());
        assertFalse(after.estimate().contradicted());
        assertTrue(
                after.estimate().exclusions().stream()
                        .anyMatch(
                                e ->
                                        e.code() == ReasonCode.UNAVAILABLE_SOURCE
                                                || e.code() == ReasonCode.SUPERSEDED));
        assertEquals(before, new EstimateRevisionRepository().find(before.id()).orElseThrow());
    }

    @Test
    void correctedBrokenCheckBecomesUnavailableRatherThanFailure() {
        var r = pass("counter-repair", 0);
        EvaluationTestFixtures.service(clock(1)).invalidate(r.id(), "broken", false);
        var e = atomic(1).estimate();
        assertEquals(Band.UNKNOWN, e.band());
        assertFalse(e.contradicted());
        assertFalse(e.regressionCap());
    }

    @Test
    void epochResetPreservesOriginalEvidenceExposureAndRevisions() throws Exception {
        pass("counter-repair", 0);
        var before = atomic(0);
        long evidence = count("evolution_evidence_observations"),
                exposures = count("evolution_challenge_presentations");
        var repo = new EstimateRevisionRepository();
        var reset = repo.reset(JavaDebuggingSkillSeed.GRAPH, "reset-learner", clock(1));
        assertEquals(reset, repo.reset(JavaDebuggingSkillSeed.GRAPH, "reset-learner", clock(2)));
        var after = atomic(2);
        assertEquals("reset-learner", after.estimate().epoch().id());
        assertEquals(Band.UNKNOWN, after.estimate().band());
        assertEquals(evidence, count("evolution_evidence_observations"));
        assertEquals(exposures, count("evolution_challenge_presentations"));
        assertEquals(before, repo.find(before.id()).orElseThrow());
        assertEquals("initial", atomic(0).estimate().epoch().id());
    }

    @Test
    void identicalInputsDoNotMintRevisionsAndConcurrentRecomputationConverges() throws Exception {
        pass("counter-repair", 0);
        var first = atomic(0);
        long count = count("evolution_estimate_revisions");
        assertEquals(first, atomic(0));
        assertEquals(count, count("evolution_estimate_revisions"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> atomic(0));
            var b = pool.submit(() -> atomic(0));
            assertEquals(first, a.get(15, TimeUnit.SECONDS));
            assertEquals(first, b.get(15, TimeUnit.SECONDS));
        }
        DatabaseConfig.initialize();
        assertEquals(first, new EstimateRevisionRepository().find(first.id()).orElseThrow());
    }

    @Test
    void mappingReplacementRemovesOldProofUntilExactNewMappingIsExplicitlyPopulated() {
        var request = pass("counter-repair", 0);
        var mapping = DurableEvaluationRepository.mapping(spec("counter-repair"));
        var replacement =
                new EvidenceMapping(
                        new EvidenceMappingRef(mapping.ref().mappingId(), 2),
                        mapping.graph(),
                        mapping.rules());
        new EvidenceMappingRepository().publish(replacement);
        assertEquals(Band.UNKNOWN, atomic(0).estimate().band());
        var view = new DurableEvaluationRepository().evaluationInput(request.id()).orElseThrow();
        new EvidenceRepository()
                .append(
                        view.observation(),
                        replacement.ref(),
                        view.observation().evaluation().familyId());
        var e =
                new MasteryEstimateService()
                        .estimate(
                                JavaDebuggingSkillSeed.GRAPH,
                                ATOMIC,
                                List.of(replacement.ref()),
                                clock(0).instant())
                        .estimate();
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(1, e.independentFamilies());
        assertEquals(Band.UNKNOWN, atomic(0).estimate().band());
    }

    @Test
    void historicalAsOfExcludesFutureSubmissionAndUnknownMappingsFailClosed() {
        pass("counter-repair", 1);
        assertEquals(Band.UNKNOWN, atomic(0).estimate().band());
        var e =
                new MasteryEstimateService()
                        .estimate(
                                JavaDebuggingSkillSeed.GRAPH,
                                ATOMIC,
                                List.of(new EvidenceMappingRef("missing", 1)),
                                clock(1).instant())
                        .estimate();
        assertEquals(Band.UNKNOWN, e.band());
        assertTrue(e.reasons().stream().anyMatch(r -> r.code() == ReasonCode.MAPPING_UNAVAILABLE));
    }

    @Test
    void baselineRejectsPracticeRoutesAndWrongReceipt() {
        var b = new BaselineDiagnosticService();
        assertThrows(
                IllegalArgumentException.class,
                () -> b.present(new CatalogRef("counter-repair", 1), Familiarity.UNKNOWN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        b.submit(
                                new CatalogRef("baseline-counter", 1),
                                new SubmissionRequest(
                                        "s",
                                        "wrong",
                                        "",
                                        EvaluationTypes.OutsideAssistance.UNKNOWN,
                                        "")));
    }

    @Test
    void sourceSnapshotContainsNoSourceAnswersOrHiddenCases() throws Exception {
        pass("counter-repair", 0);
        atomic(0);
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT inputs FROM evolution_estimate_revisions")) {
            while (r.next()) {
                var input = EstimateCodec.decode(r.getBytes(1));
                assertTrue(
                        input.observations().stream()
                                .allMatch(
                                        o ->
                                                o.context() == null
                                                        || o.context().checks().stream()
                                                                .allMatch(
                                                                        check ->
                                                                                check.limitations()
                                                                                        != null)));
                String raw =
                        new String(r.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("class Exercise"));
                assertFalse(raw.contains("public class Main"));
            }
        }
    }

    @Test
    void correctionBetweenReadAndPublishCannotSaveStalePositive() throws Exception {
        var request = pass("counter-repair", 0);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var service =
                new MasteryEstimateService(
                        () -> {
                            if (once.compareAndSet(false, true))
                                EvaluationTestFixtures.service(clock(1))
                                        .invalidate(request.id(), "concurrent-withdrawal", true);
                        });
        var ref = DurableEvaluationRepository.mapping(spec("counter-repair")).ref();
        var estimate =
                service.estimate(
                        JavaDebuggingSkillSeed.GRAPH, ATOMIC, List.of(ref), clock(1).instant());
        assertEquals(Band.UNKNOWN, estimate.estimate().band());
        assertEquals(1, count("evolution_estimate_revisions"));
    }

    @Test
    void unIngestedRecallStillInterruptsRetention() throws Exception {
        pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        pass("inventory-transfer", 2);
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "INSERT INTO flashcards(deck_id,front,back,due_date,skill_category) SELECT"
                        + " MIN(id),'Recall fixture','scope','2026-06-12','JCIP Ch15 - Atomics and"
                        + " Nonblocking' FROM decks");
            s.execute(
                    "INSERT INTO"
                        + " review_history(flashcard_id,rating,previous_interval_days,new_interval_days,reviewed_at,validation_result)"
                        + " SELECT MIN(id),'GOOD',1,2,'2026-06-20 12:00:00','EXACT' FROM flashcards"
                        + " WHERE skill_category='JCIP Ch15 - Atomics and Nonblocking'");
        }
        pass("queue-retention", 9);
        assertEquals(Band.TRANSFER_SUPPORTED, atomic(9).estimate().band());
    }

    @Test
    void realDeliberateMutantFailuresContradictAndCapWithoutErasingSuccesses() {
        pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        for (int day = 2; day <= 3; day++) {
            var request =
                    request(
                            "atomic-boundary-drill",
                            day,
                            AssetKind.MUTANT,
                            EvaluationTypes.OutsideAssistance.NONE_DECLARED);
            var outcome = EvaluationTestFixtures.service(clock(day)).submit(request).outcome();
            assertTrue(
                    outcome == EvaluationTypes.Outcome.FAILED
                            || outcome == EvaluationTypes.Outcome.PARTIAL);
        }
        var e = atomic(3).estimate();
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(Band.INDEPENDENT, e.historicalBand());
        assertTrue(e.regressionCap());
        assertTrue(e.contradicted());
        assertEquals(
                2,
                e.contributions().stream()
                        .filter(MasteryEstimate.Contribution::eligibleDeliberateFailure)
                        .count());
    }

    @Test
    void pendingReplacementMappingBlocksAdvancementDespiteOtherPositiveFamilies() {
        var original = pass("counter-repair", 0);
        pass("remaining-capacity", 1);
        pass("distinct-id-accumulator", 2);
        var old = DurableEvaluationRepository.mapping(spec("counter-repair"));
        var next =
                new EvidenceMapping(
                        new EvidenceMappingRef(old.ref().mappingId(), 2), old.graph(), old.rules());
        new EvidenceMappingRepository().publish(next);
        var refs = new ArrayList<>(new MasteryEstimateService().pilotMappings());
        refs.remove(old.ref());
        refs.add(next.ref());
        var pending =
                new MasteryEstimateService()
                        .estimate(JavaDebuggingSkillSeed.GRAPH, ATOMIC, refs, clock(2).instant())
                        .estimate();
        assertEquals(Band.INDEPENDENT, pending.band());
        assertEquals(Support.INSUFFICIENT, pending.support());
        assertFalse(pending.currentCapabilitySupported());
        var facts =
                new DurableEvaluationRepository()
                        .evaluationInput(original.id())
                        .orElseThrow()
                        .observation();
        new EvidenceRepository().append(facts, next.ref(), facts.evaluation().familyId());
        var complete =
                new MasteryEstimateService()
                        .estimate(JavaDebuggingSkillSeed.GRAPH, ATOMIC, refs, clock(2).instant())
                        .estimate();
        assertTrue(complete.currentCapabilitySupported());
        assertEquals(3, complete.independentFamilies());
    }

    @Test
    void baselineKeyedDiagnosisIsUsableButCannotClaimCodeRepairIndependence() {
        var baseline =
                new BaselineDiagnosticService(
                        presentations(0), EvaluationTestFixtures.service(clock(0)), clock(0));
        var ref = new CatalogRef("baseline-trace", 1);
        var view = baseline.present(ref, Familiarity.DECLARED_UNFAMILIAR);
        var definition = spec(ref.id());
        var key =
                assets.read(
                        definition.assets().stream()
                                .filter(a -> a.kind() == AssetKind.REFERENCE)
                                .findFirst()
                                .orElseThrow());
        var request =
                new SubmissionRequest(
                        "baseline-keyed",
                        view.receipt().id(),
                        key,
                        EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                        "");
        assertEquals(EvaluationTypes.Outcome.PASSED, baseline.submit(ref, request).outcome());
        var reproduce =
                baseline.status(clock(1).instant()).estimates().stream()
                        .map(EstimateRevisionRepository.Revision::estimate)
                        .filter(e -> e.skill().skillId().equals("java.debugging.reproduce"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Band.DEVELOPING, reproduce.band());
        assertEquals(0, reproduce.independentFamilies());
        assertTrue(
                reproduce.reasons().stream()
                        .anyMatch(r -> r.code() == ReasonCode.DIAGNOSTIC_SCOPE));
    }

    @Test
    void resetAtSameInstantAndLaterCorrectionsCannotResurrectOldSource() {
        var request = pass("counter-repair", 0);
        assertEquals(Band.DEVELOPING, atomic(0).estimate().band());
        var reset =
                new EstimateRevisionRepository()
                        .reset(JavaDebuggingSkillSeed.GRAPH, "same-instant-reset", clock(0));
        assertTrue(reset.evidenceThrough() > 0);
        assertEquals(Band.UNKNOWN, atomic(0).estimate().band());
        var mapping = DurableEvaluationRepository.mapping(spec("counter-repair"));
        var next =
                new EvidenceMapping(
                        new EvidenceMappingRef(mapping.ref().mappingId(), 2),
                        mapping.graph(),
                        mapping.rules());
        new EvidenceMappingRepository().publish(next);
        var observation =
                new DurableEvaluationRepository()
                        .evaluationInput(request.id())
                        .orElseThrow()
                        .observation();
        new EvidenceRepository()
                .append(observation, next.ref(), observation.evaluation().familyId());
        assertEquals(
                Band.UNKNOWN,
                new MasteryEstimateService()
                        .estimate(
                                JavaDebuggingSkillSeed.GRAPH,
                                ATOMIC,
                                List.of(next.ref()),
                                clock(1).instant())
                        .estimate()
                        .band());
    }
}
