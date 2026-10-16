package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.quest.ChallengePresentation.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ChallengePresentationIntegrationTest {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private ChallengePresentationService service() {
        return new ChallengePresentationService(
                catalog, new BundledAssets(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Request request(String id, String challenge) {
        return new Request(
                id,
                new CatalogRef("broken-counter", 1),
                new CatalogRef(challenge, 1),
                Cohort.PRACTICE,
                Familiarity.UNKNOWN);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void commitPrecedesReturnAndCommandReplaySurvivesRestart() {
        String id = id();
        var request = request(id, "counter-repair");
        var first = service().present(request);
        assertEquals(NOW, first.receipt().presentedAt());
        assertNotNull(
                new ProblemSolvingSessionRepository()
                        .findByProblemId(first.backing().problemId())
                        .orElseThrow());
        assertEquals(
                first.draft().getId(),
                new JavaSolutionDraftRepository()
                        .findByProblemId(first.backing().problemId())
                        .orElseThrow()
                        .getId());
        service().saveDraft(id, "// private edits");
        DatabaseConfig.initialize();
        var again = service().present(request);
        assertEquals(first.receipt(), again.receipt());
        assertEquals("// private edits", again.draft().getSourceCode());
        assertEquals(
                1,
                service().history("metric-counter").stream()
                        .filter(p -> p.id().equals(id))
                        .count());
        assertTrue(
                again.publicChecks().stream().allMatch(c -> c.visibility() == Visibility.PUBLIC));
    }

    @Test
    void sameCommandCannotChangeChallengeQuestCohortOrFamiliarity() {
        String id = id();
        var r = request(id, "counter-repair");
        service().present(r);
        for (var conflict :
                List.of(
                        request(id, "counter-repair-b"),
                        new Request(id, r.quest(), r.challenge(), Cohort.TRANSFER, r.declaration()),
                        new Request(
                                id,
                                r.quest(),
                                r.challenge(),
                                r.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR)))
            assertThrows(IllegalArgumentException.class, () -> service().present(conflict));
    }

    @Test
    void newVariantRetainsFamilyAidsAndCannotBecomeIndependent() {
        String first = id();
        service().present(request(first, "counter-repair"));
        service().revealHint(first, id(), 2);
        service().revealSolution(first, id());
        service().skip(first, id());
        String second = id();
        var related = service().present(request(second, "counter-repair-b"));
        assertTrue(related.receipt().prior().familyPresentations() > 0);
        assertTrue(related.currentContext().solutionRevealed());
        assertTrue(related.currentContext().highestHint() >= 2);
        assertNotEquals(PresentationKind.FIRST_PRESENTATION, related.receipt().prior().kind());
        var repeat = service().present(request(id(), "counter-repair-b"));
        assertEquals(PresentationKind.REPEAT, repeat.receipt().prior().kind());
    }

    @Test
    void independentFamilyHasNoInventedUnseenProof() {
        var r =
                new Request(
                        id(),
                        new CatalogRef("investigate-regression", 1),
                        new CatalogRef("regression-localize", 1),
                        Cohort.PRACTICE,
                        Familiarity.UNKNOWN);
        var p = service().present(r);
        assertEquals(PresentationKind.FIRST_PRESENTATION, p.receipt().prior().kind());
        assertEquals(Familiarity.UNKNOWN, p.receipt().declaration());
        assertFalse(p.currentContext().knownFamiliar());
    }

    @Test
    void explicitFamiliarityCannotBeErasedByUnfamiliarDeclaration() {
        String first = id();
        var r =
                new Request(
                        first,
                        new CatalogRef("warehouse-transfer", 1),
                        new CatalogRef("inventory-transfer", 1),
                        Cohort.TRANSFER,
                        Familiarity.DECLARED_FAMILIAR);
        service().present(r);
        var next =
                service()
                        .present(
                                new Request(
                                        id(),
                                        r.quest(),
                                        r.challenge(),
                                        r.cohort(),
                                        Familiarity.DECLARED_UNFAMILIAR));
        assertTrue(next.receipt().prior().knownFamiliar());
        assertTrue(next.currentContext().knownFamiliar());
    }

    @Test
    void hintSolutionAndSkipEventsAreIdempotentAndConflictChecked() {
        String p = id(), event = id();
        service().present(request(p, "distinct-id-accumulator"));
        String hint = service().revealHint(p, event, 1);
        assertEquals(hint, service().revealHint(p, event, 1));
        assertThrows(IllegalArgumentException.class, () -> service().revealHint(p, event, 2));
        assertThrows(IllegalArgumentException.class, () -> service().revealSolution(p, event));
        service().skip(p, id());
        service().declareFamiliar(p, id());
        DatabaseConfig.initialize();
        assertTrue(service().resume(p).currentContext().knownFamiliar());
        assertTrue(service().resume(p).currentContext().highestHint() >= 1);
        assertThrows(IllegalArgumentException.class, () -> service().resume("never-presented"));
    }

    @Test
    void rejectedRequestAndMissingAssetsLeaveNoPresentationOrDraft() {
        String id = id();
        var bad =
                new Request(
                        id,
                        new CatalogRef("initial-diagnostic", 1),
                        new CatalogRef("counter-repair", 1),
                        Cohort.PRACTICE,
                        Familiarity.UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> service().present(bad));
        assertThrows(IllegalArgumentException.class, () -> service().resume(id));
        var failAssets =
                new BundledAssets() {
                    @Override
                    public void validate(ChallengeSpec spec) {
                        throw new IllegalStateException("Simulated missing resource");
                    }
                };
        var service =
                new ChallengePresentationService(
                        catalog, failAssets, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(
                IllegalStateException.class, () -> service.present(request(id, "counter-repair")));
        assertThrows(IllegalArgumentException.class, () -> service().resume(id));
    }

    @Test
    void crashAfterPresentationWriteRollsBackAndCanRetry() {
        String id = id();
        assertThrows(
                IllegalStateException.class,
                () ->
                        catalog.transaction(
                                c -> {
                                    new ChallengeExposureRepository()
                                            .append(c, request(id, "counter-repair"), NOW);
                                    throw new IllegalStateException(
                                            "simulated crash before commit");
                                }));
        assertThrows(IllegalArgumentException.class, () -> service().resume(id));
        assertNotNull(service().present(request(id, "counter-repair")));
    }

    @Test
    void committedReceiptSurvivesLostRenderingResponse() {
        String id = id();
        catalog.transaction(
                c -> {
                    new ChallengeExposureRepository()
                            .append(c, request(id, "remaining-capacity"), NOW);
                    return null;
                });
        DatabaseConfig.initialize();
        var view = service().present(request(id, "remaining-capacity"));
        assertEquals(NOW, view.receipt().presentedAt());
        assertEquals(
                1,
                service().history("bounded-capacity").stream()
                        .filter(p -> p.id().equals(id))
                        .count());
    }

    @Test
    void concurrentDuplicatePresentationsCommitOnce() throws Exception {
        String id = id();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<ChallengePresentation> work =
                    () -> {
                        gate.await();
                        return service().present(request(id, "counter-repair")).receipt();
                    };
            var a = pool.submit(work);
            var b = pool.submit(work);
            gate.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(
                1,
                service().history("metric-counter").stream()
                        .filter(p -> p.id().equals(id))
                        .count());
    }

    @Test
    void presentingSkippingRevealingCreatesNoAttemptEvidenceXpOrPeerPublication() {
        var before = counts();
        String p = id();
        service().present(request(p, "atomic-boundary-drill"));
        service().revealHint(p, id(), 3);
        service().revealSolution(p, id());
        service().skip(p, id());
        assertEquals(before, counts());
    }

    @Test
    void versionChangeRetainsVariantIdentityAndAssistance() {
        var old = catalog.findChallenge(new CatalogRef("counter-repair", 1)).orElseThrow();
        var next =
                CatalogCodec.challenge(
                        CatalogCodec.encode(old)
                                .replace("challenge.version=1", "challenge.version=2"));
        var quest = catalog.findQuest(new CatalogRef("broken-counter", 1)).orElseThrow();
        var nextQuest =
                CatalogCodec.quest(
                        CatalogCodec.encode(quest)
                                .replace("quest.version=1", "quest.version=2")
                                .replace("challenges.1.version=1", "challenges.1.version=2"));
        String first = id();
        service().present(request(first, "counter-repair"));
        service().revealSolution(first, id());
        catalog.publish(List.of(nextQuest), List.of(next));
        var view =
                service()
                        .present(
                                new Request(
                                        id(),
                                        nextQuest.ref(),
                                        next.ref(),
                                        Cohort.PRACTICE,
                                        Familiarity.UNKNOWN));
        assertEquals(PresentationKind.REPEAT, view.receipt().prior().kind());
        assertTrue(view.receipt().prior().solutionRevealed());
    }

    @Test
    void keyedDiagnosticUsesReservedAssessmentAndNoJavaDraftOrProblem() {
        var request =
                new Request(
                        id(),
                        new CatalogRef("initial-diagnostic", 1),
                        new CatalogRef("baseline-trace", 1),
                        Cohort.BASELINE,
                        Familiarity.UNKNOWN);
        var view = service().present(request);
        assertNull(view.backing().problemId());
        assertNotNull(view.backing().assessmentItemId());
        assertNull(view.draft());
        assertTrue(
                new AssessmentItemRepository()
                        .findById(view.backing().assessmentItemId())
                        .isEmpty());
        assertFalse(view.prompt().isBlank());
        assertThrows(
                IllegalArgumentException.class,
                () -> service().saveDraft(request.id(), "not code"));
        assertTrue(
                service().resume(request.id()).publicChecks().stream()
                        .noneMatch(c -> c.kind() == CheckKind.KEYED_DIAGNOSTIC));
    }

    @Test
    void legacyDraftAndHintStateMarkFamiliarityBeforeFirstRegisteredPresentation() {
        var ref = new CatalogRef("lock-order-repair", 1);
        long problem = catalog.transaction(c -> catalog.problemId(c, ref));
        new JavaSolutionDraftRepository()
                .save(
                        new com.codefit.model.JavaSolutionDraft(
                                0, problem, "Main", "private earlier work", "", "", null));
        var view =
                service()
                        .present(
                                new Request(
                                        id(),
                                        new CatalogRef("unlock-the-workers", 1),
                                        ref,
                                        Cohort.PRACTICE,
                                        Familiarity.UNKNOWN));
        assertTrue(view.receipt().prior().knownFamiliar());
        assertTrue(view.currentContext().knownFamiliar());
        assertEquals("private earlier work", view.draft().getSourceCode());
    }

    @Test
    void repositoryMutationsRequireForeignKeyEnabledCallerTransaction() throws Exception {
        try (var c = DatabaseConfig.getConnection()) {
            var exposure = new ChallengeExposureRepository();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> exposure.append(c, request(id(), "counter-repair"), NOW));
            c.setAutoCommit(false);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> exposure.append(c, request(id(), "counter-repair"), NOW));
            c.rollback();
        }
    }

    private List<Long> counts() {
        return catalog.transaction(
                c -> {
                    var result = new ArrayList<Long>();
                    for (String q :
                            List.of(
                                    "SELECT COUNT(*) FROM problem_attempts",
                                    "SELECT COUNT(*) FROM assessment_attempts",
                                    "SELECT COUNT(*) FROM evolution_evidence_observations",
                                    "SELECT xp FROM user_progress WHERE id=1",
                                    "SELECT COUNT(*) FROM publication_outbox",
                                    "SELECT COUNT(*) FROM peer_progress_summaries",
                                    "SELECT COUNT(*) FROM peer_preparation_snapshots"))
                        try (var s = c.createStatement();
                                var rs = s.executeQuery(q)) {
                            rs.next();
                            result.add(rs.getLong(1));
                        }
                    return result;
                });
    }
}
