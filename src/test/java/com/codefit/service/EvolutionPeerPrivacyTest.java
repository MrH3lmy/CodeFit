package com.codefit.service;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.quest.*;
import com.codefit.model.*;
import com.codefit.peer.identity.*;
import com.codefit.peer.protocol.*;
import com.codefit.repository.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvolutionPeerPrivacyTest {
    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final QuestCatalogRepository tx = new QuestCatalogRepository();

    @Test
    void
            allPeerAggregatePathsAndRealSignedPayloadsExcludeReservedAttemptsRegardlessOfVerdictOrOrigin() {
        tx.transaction(
                c -> {
                    try (var s = c.createStatement()) {
                        s.execute(
                                "INSERT INTO problems(id,external_code,platform,title,topic)"
                                        + " VALUES(970001,'normal','Codeforces','Ordinary"
                                        + " activity','Ordinary topic')");
                        s.execute(
                                "INSERT INTO"
                                    + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin,submitted_at,reading_time_seconds)"
                                    + " VALUES(970001,1,'AC','FRESH_ATTEMPT','2026-06-12"
                                    + " 09:30:00',17)");
                    }
                    return null;
                });
        var attempts = new ProblemAttemptRepository();
        var start = LocalDateTime.of(2026, 6, 12, 0, 0);
        var end = start.plusDays(1);
        var ordinary = attempts.findSubmittedBetweenUtc(start, end);
        int fresh = attempts.countFreshFirstCompletionsBetweenUtc(start, end);
        boolean ambiguous = attempts.hasUnknownSubmittedBetweenUtc(start, end);
        var snapshots =
                new ProgressSnapshotService(
                        new ReviewHistoryRepository(),
                        attempts,
                        new InterviewMockRepository(),
                        new LocalProgressSnapshotRepository(),
                        CLOCK);
        var contacts = new ContactService();
        var ids = new IdentityService();
        ids.createIdentity("private-test".toCharArray(), NOW.minusSeconds(86400));
        ids.resumeSharingAfterReview();
        var identity = ids.unlock("private-test".toCharArray());
        byte[] recipientKey = new byte[32];
        Arrays.fill(recipientKey, (byte) 7);
        var pending =
                contacts.registerPendingContact(
                        new IdentityKey(recipientKey), "Contact", NOW.minusSeconds(86400));
        var contact = contacts.acceptInvitation(pending.id(), NOW.minusSeconds(86400));
        contacts.updatePermissions(
                contact.id(),
                new PermissionGrant(
                        List.of(
                                SharingScope.DAILY_SUMMARY,
                                SharingScope.WEEKLY_SUMMARY,
                                SharingScope.PREPARATION_SNAPSHOT),
                        30,
                        null,
                        false),
                NOW.minusSeconds(86400));
        var publication =
                new SnapshotPublicationService(
                        contacts,
                        snapshots,
                        new PreparationSnapshotCaptureService(
                                new InterviewReadinessService(),
                                new InterviewProfileService(),
                                new LocalPreparationCheckpointRepository(),
                                CLOCK),
                        new PreparationSnapshotWireStateRepository(),
                        CLOCK);
        var windows =
                List.of(
                        ComparisonWindow.day(LocalDate.of(2026, 6, 12), ZoneOffset.UTC),
                        ComparisonWindow.week(
                                LocalDate.of(2026, 6, 8), ZoneOffset.UTC, DayOfWeek.MONDAY),
                        ComparisonWindow.day(
                                LocalDate.of(2026, 6, 12), ZoneId.of("America/New_York")));
        String profile = new InterviewProfileService().getRevolutJavaProfile().getId();
        var prepBefore = publication.publishPreparationSnapshot(contact.id(), identity, 1, profile);
        var before =
                windows.stream()
                        .map(w -> publication.publishProgressSummary(contact.id(), identity, 1, w))
                        .toList();
        tx.transaction(
                c -> {
                    long problem = tx.problemId(c, new CatalogRef("counter-repair", 1));
                    var local =
                            attempts.saveLocalEvaluation(
                                    c,
                                    new ProblemAttempt(
                                            0,
                                            problem,
                                            1,
                                            SubmissionResult.LOCAL_EVALUATION,
                                            101,
                                            202,
                                            303,
                                            404,
                                            LocalDateTime.of(2026, 6, 12, 10, 0),
                                            "PRIVATE_EVOLUTION_SOURCE",
                                            SessionFinishOutcome.SUBMITTED,
                                            CompletionOrigin.FRESH_ATTEMPT));
                    assertEquals(SubmissionResult.LOCAL_EVALUATION, local.submissionResult());
                    // Adversarial legacy/import rows: platform exclusion must hold even if the
                    // verdict or
                    // origin marker is wrong. In particular UNKNOWN must not poison availability.
                    int number = 2;
                    for (String origin :
                            List.of("FRESH_ATTEMPT", "PREVIOUSLY_SOLVED", "UNKNOWN", "IMPORTED"))
                        for (String verdict : List.of("AC", "ACX", "WA")) {
                            try (var s =
                                    c.prepareStatement(
                                            "INSERT INTO"
                                                + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin,submitted_at,debugging_time_seconds,notes)"
                                                + " VALUES(?,?,?,?,'2026-06-12"
                                                + " 10:00:00',999,'PRIVATE_SUBMISSION_EVIDENCE')")) {
                                s.setLong(1, problem);
                                s.setInt(2, number++);
                                s.setString(3, verdict);
                                s.setString(4, origin);
                                s.executeUpdate();
                            }
                        }
                    return null;
                });
        // Exercise the real finalization, replay and correction pipeline inside the comparison
        // interval too; privacy must hold for genuine writes, not only synthetic query fixtures.
        var submitClock = Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC);
        var presenter = new ChallengePresentationService(tx, new BundledAssets(), submitClock);
        var engine = com.codefit.evolution.evaluation.EvaluationTestFixtures.service(submitClock);
        for (String challenge : List.of("counter-repair", "baseline-trace")) {
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
            String presentation = "private-presentation-" + challenge;
            presenter.present(
                    new ChallengePresentation.Request(
                            presentation,
                            quest.ref(),
                            spec.ref(),
                            spec.cohort(),
                            QuestTypes.Familiarity.UNKNOWN));
            var reference =
                    spec.assets().stream()
                            .filter(a -> a.kind() == QuestTypes.AssetKind.REFERENCE)
                            .findFirst()
                            .orElseThrow();
            var request =
                    new com.codefit.evolution.evaluation.SubmissionRequest(
                            "private-submission-" + challenge,
                            presentation,
                            new BundledAssets().read(reference),
                            com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance
                                    .UNKNOWN,
                            "");
            var result = engine.submit(request);
            assertEquals(
                    com.codefit.evolution.evaluation.EvaluationTypes.Outcome.PASSED,
                    result.outcome());
            assertEquals(result, engine.submit(request));
            engine.invalidate(request.id(), "private-correction-" + challenge, false);
            engine.invalidate(request.id(), "private-withdrawal-" + challenge, true);
        }
        // Estimate revisions, baseline preferences and resets are private derived state too.
        var estimates = new com.codefit.evolution.mastery.MasteryEstimateService();
        estimates.pilot(NOW);
        new com.codefit.evolution.mastery.EstimateRevisionRepository().reset(
                com.codefit.config.JavaDebuggingSkillSeed.GRAPH, "private-estimate-reset", CLOCK);
        new com.codefit.evolution.mastery.BaselineDiagnosticService().skipAll("private-skip-baseline");
        estimates.pilot(NOW);
        // New adaptation interpretations and explicit learner controls remain private too.
        var adaptation = new com.codefit.evolution.adaptation.AdaptiveQuestService(
                CLOCK, new BundledAssets(), () -> true, new ProblemLibraryService());
        var decision = adaptation.recommend(NOW);
        adaptation.respond(com.codefit.evolution.adaptation.LearnerCommand.simple(
                "private-adaptation-pause", com.codefit.evolution.adaptation.AdaptationTypes.CommandKind.PAUSE));
        adaptation.respond(com.codefit.evolution.adaptation.LearnerCommand.simple(
                "private-adaptation-resume", com.codefit.evolution.adaptation.AdaptationTypes.CommandKind.RESUME));
        adaptation.recommend(NOW);
        assertEquals(decision, new com.codefit.evolution.adaptation.AdaptationDecisionRepository().find(decision.id()).orElseThrow());
        assertEquals(ordinary, attempts.findSubmittedBetweenUtc(start, end));
        assertEquals(fresh, attempts.countFreshFirstCompletionsBetweenUtc(start, end));
        assertEquals(ambiguous, attempts.hasUnknownSubmittedBetweenUtc(start, end));
        assertEquals(ordinary, attempts.findAllOrdinary());
        for (int i = 0; i < windows.size(); i++) {
            var after =
                    publication.publishProgressSummary(contact.id(), identity, 1, windows.get(i));
            assertEquals(
                    before.get(i),
                    after,
                    "Private attempts cannot change the signed body, revision or bytes");
            assertTrue(after.verifySignature());
            byte[] frame = EnvelopeCodec.encodeFrame(after);
            assertEquals(after, EnvelopeCodec.decodeFrame(frame));
            String bytes = new String(frame, StandardCharsets.ISO_8859_1);
            for (String secret :
                    List.of(
                            "CODEFIT_EVOLUTION",
                            "PRIVATE_EVOLUTION_SOURCE",
                            "PRIVATE_SUBMISSION_EVIDENCE",
                            "private-submission-",
                            "private-presentation-",
                            "private-correction-",
                            "counter-repair",
                            "LOCAL_EVALUATION",
                            "evolution_evidence",
                            "artifact_sha",
                            "ordinal-v1", "private-estimate-reset", "private-skip-baseline",
                            "evolution_estimate", "NO_DIRECT_EVIDENCE", "adaptive-v1", "java-pilot-v1",
                            "private-adaptation-pause", "evolution_adaptation", "decision-")) assertFalse(bytes.contains(secret), secret);
        }
        var prepAfter = publication.publishPreparationSnapshot(contact.id(), identity, 1, profile);
        assertEquals(prepBefore, prepAfter);
        assertTrue(prepAfter.verifySignature());
        var daily = (ProgressSummary) before.getFirst().body();
        assertEquals(
                1,
                daily.metrics().stream()
                        .filter(m -> m.metricId().equals("problem.attempts"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        assertEquals(
                17,
                daily.metrics().stream()
                        .filter(m -> m.metricId().equals("problem.solving_seconds"))
                        .findFirst()
                        .orElseThrow()
                        .value());
    }
}
