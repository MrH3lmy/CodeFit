package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.mastery.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PracticeSkillsIntegrationTest {
    @TempDir Path dir;
    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    String uuid() {
        return UUID.randomUUID().toString();
    }

    Map<String, String> database(ExperimentalProfile p, boolean excludeAllowed) throws Exception {
        var result = new TreeMap<String, String>();
        try (var c = p.connection(true);
                var q = c.createStatement();
                var rows =
                        q.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT"
                                        + " LIKE 'sqlite_%' ORDER BY name")) {
            while (rows.next()) {
                String table = rows.getString(1);
                if (excludeAllowed
                        && (table.startsWith("desktop_skill_")
                                || table.equals("evolution_estimate_revisions"))) continue;
                var values = new ArrayList<String>();
                try (var s = c.createStatement();
                        var r = s.executeQuery("SELECT * FROM \"" + table + "\"")) {
                    while (r.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                            row.add(
                                    Base64.getEncoder()
                                            .encodeToString(
                                                    Optional.ofNullable(r.getBytes(i))
                                                            .orElse(new byte[0])));
                        values.add(row.toString());
                    }
                }
                Collections.sort(values);
                result.put(table, values.toString());
            }
        }
        return result;
    }

    long count(ExperimentalProfile p, String table) throws Exception {
        try (var c = p.connection(true);
                var q = c.createStatement();
                var r = q.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void emptyProfileAtomicReplayAndRestartAreReadOnlyExceptEstimatesAndReceipts()
            throws Exception {
        String id = uuid(), session = uuid();
        PracticeSkillsService.Result receipt;
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var service = new PracticeSkillsService(p, CLOCK);
            var before = database(p, true);
            receipt = service.reconcile(id, session);
            assertEquals("PUBLISHED", receipt.state());
            assertEquals(4, receipt.skills().size());
            assertTrue(
                    receipt.skills().stream()
                            .allMatch(
                                    s ->
                                            s.estimate().band() == MasteryTypes.Band.UNKNOWN
                                                    && s.created()));
            assertEquals(before, database(p, true));
            var after = database(p, false);
            assertEquals(receipt, service.reconcile(id, session));
            assertEquals(receipt, service.status(id, session));
            assertEquals(receipt, service.latest());
            assertEquals(after, database(p, false));
            assertThrows(IllegalArgumentException.class, () -> service.reconcile(id, uuid()));
            var same = service.reconcile(uuid(), session);
            assertTrue(same.skills().stream().noneMatch(PracticeSkillsService.Item::created));
            assertEquals(4, count(p, "evolution_estimate_revisions"));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            assertEquals(receipt, new PracticeSkillsService(p, CLOCK).status(id, session));
        }
    }

    static final class Crash extends Error {}

    @ParameterizedTest
    @EnumSource(PracticeSkillsService.Boundary.class)
    void crashNeverPartiallyPublishesOrRecaptures(PracticeSkillsService.Boundary boundary)
            throws Exception {
        String id = uuid(), session = uuid();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var service =
                    new PracticeSkillsService(
                            p,
                            CLOCK,
                            b -> {
                                if (b == boundary) throw new Crash();
                            });
            assertThrows(Crash.class, () -> service.reconcile(id, session));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var service =
                    new PracticeSkillsService(
                            p, Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
            var before = database(p, false);
            var result = service.status(id, session);
            assertEquals(
                    boundary == PracticeSkillsService.Boundary.AFTER_COMMIT
                            ? "PUBLISHED"
                            : "INTERRUPTED",
                    result.state());
            assertEquals(result, service.reconcile(id, session));
            assertEquals(before, database(p, false));
            assertEquals(
                    boundary == PracticeSkillsService.Boundary.AFTER_COMMIT ? 4 : 0,
                    count(p, "evolution_estimate_revisions"));
        }
    }

    @Test
    void sourceChangeAfterCaptureRejectsAllSkills() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var service =
                    new PracticeSkillsService(
                            p,
                            CLOCK,
                            b -> {
                                if (b == PracticeSkillsService.Boundary.CAPTURED)
                                    try (var c = p.connection(false);
                                            var s = c.createStatement()) {
                                        s.executeUpdate(
                                                "INSERT INTO"
                                                    + " evolution_estimate_epochs(id,graph_id,graph_version,started_at,evidence_through)"
                                                    + " VALUES('changed-epoch','java-debugging-concurrency',1,'2026-10-28T11:00:00Z',0)");
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                            });
            var r = service.reconcile(uuid(), uuid());
            assertEquals("REJECTED", r.state());
            assertEquals("SOURCE_CHANGED", r.reason());
            assertEquals(0, count(p, "evolution_estimate_revisions"));
        }
    }

    @Test
    void unknownCommandAndLibraryOwnerCannotPublish() throws Exception {
        try (var p = ExperimentalProfile.open(dir.resolve("library"))) {
            assertThrows(IllegalArgumentException.class, () -> new PracticeSkillsService(p, CLOCK));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var s = new PracticeSkillsService(p, CLOCK);
            var before = database(p, false);
            assertNull(s.status(uuid(), uuid()));
            assertEquals(before, database(p, false));
        }
    }

    String evaluation(
            String challenge,
            int days,
            com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance aid)
            throws Exception {
        return evaluation(challenge, days, aid, false);
    }

    String evaluation(
            String challenge,
            int days,
            com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance aid,
            boolean mutant)
            throws Exception {
        var spec =
                com.codefit.evolution.quest.JavaTrainingPack.challenges().stream()
                        .filter(x -> x.ref().id().equals(challenge))
                        .findFirst()
                        .orElseThrow();
        var quest =
                com.codefit.evolution.quest.JavaTrainingPack.quests().stream()
                        .filter(x -> x.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        var assets = new com.codefit.evolution.quest.BundledAssets();
        var clock = Clock.fixed(NOW.plusSeconds(days * 86400L), ZoneOffset.UTC);
        String presentation = "skill-test-" + uuid(), command = uuid();
        new com.codefit.service.ChallengePresentationService(
                        new com.codefit.repository.QuestCatalogRepository(), assets, clock)
                .present(
                        new com.codefit.evolution.quest.ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                spec.ref(),
                                spec.cohort(),
                                com.codefit.evolution.quest.QuestTypes.Familiarity
                                        .DECLARED_UNFAMILIAR));
        String source =
                assets.read(
                        spec.assets().stream()
                                .filter(
                                        a ->
                                                a.kind()
                                                        == (mutant
                                                                ? com.codefit.evolution.quest
                                                                        .QuestTypes.AssetKind.MUTANT
                                                                : com.codefit.evolution.quest
                                                                        .QuestTypes.AssetKind
                                                                        .REFERENCE))
                                .findFirst()
                                .orElseThrow());
        var request =
                new com.codefit.evolution.evaluation.SubmissionRequest(
                        command, presentation, source, aid, "");
        var outcome =
                com.codefit.evolution.evaluation.EvaluationTestFixtures.service(clock)
                        .submit(request)
                        .outcome();
        if (mutant)
            assertTrue(
                    outcome == com.codefit.evolution.evaluation.EvaluationTypes.Outcome.FAILED
                            || outcome
                                    == com.codefit.evolution.evaluation.EvaluationTypes.Outcome
                                            .PARTIAL);
        else assertEquals(com.codefit.evolution.evaluation.EvaluationTypes.Outcome.PASSED, outcome);
        return command;
    }

    PracticeSkillsService.Item atomic(PracticeSkillsService.Result r) {
        assertEquals("PUBLISHED", r.state());
        return r.skills().stream()
                .filter(x -> x.skillId().equals("java.concurrency.atomic-update"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void realIndependentFamiliesWithdrawalsCorrectionsAndStalenessUseExistingPolicy()
            throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseFile(p.database());
            String first =
                    evaluation(
                            "counter-repair",
                            -3,
                            com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance
                                    .NONE_DECLARED);
            String second =
                    evaluation(
                            "remaining-capacity",
                            -2,
                            com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance
                                    .NONE_DECLARED);
            var before = database(p, true);
            var service = new PracticeSkillsService(p, CLOCK);
            var result = service.reconcile(uuid(), uuid());
            assertEquals(MasteryTypes.Band.INDEPENDENT, atomic(result).estimate().band());
            assertEquals(before, database(p, true));
            service =
                    new PracticeSkillsService(
                            p, Clock.fixed(NOW.plusSeconds(40 * 86400L), ZoneOffset.UTC));
            var stale = service.reconcile(uuid(), uuid());
            assertEquals(MasteryTypes.Freshness.STALE, atomic(stale).estimate().freshness());
            new com.codefit.evolution.evaluation.ChallengeEvaluationCoordinator(CLOCK)
                    .invalidate(second, uuid(), true);
            before = database(p, true);
            var withdrawn = service.reconcile(uuid(), uuid());
            assertEquals(MasteryTypes.Band.DEVELOPING, atomic(withdrawn).estimate().band());
            assertEquals(before, database(p, true));
            new com.codefit.evolution.evaluation.ChallengeEvaluationCoordinator(CLOCK)
                    .invalidate(first, uuid(), false);
            assertNotEquals(
                    MasteryTypes.Band.INDEPENDENT,
                    atomic(service.reconcile(uuid(), uuid())).estimate().band());
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
    }

    @Test
    void assistedSuccessDoesNotBecomeIndependent() throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseFile(p.database());
            evaluation(
                    "counter-repair",
                    -1,
                    com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance.DECLARED);
            var r = new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid());
            assertEquals(MasteryTypes.Band.DEVELOPING, atomic(r).estimate().band());
            assertEquals(0, atomic(r).estimate().independentFamilies());
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
    }

    @Test
    void supersededMappingAndFutureEpochFailClosed() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                var repo = new com.codefit.repository.EvidenceMappingRepository();
                var old =
                        repo.find(
                                        c,
                                        com.codefit.repository.EvidenceMappingRepository
                                                .INITIAL_MAPPING)
                                .orElseThrow();
                repo.publish(
                        c,
                        new com.codefit.evolution.evidence.EvidenceMapping(
                                new com.codefit.evolution.evidence.EvidenceMappingRef(
                                        old.ref().mappingId(), 2),
                                old.graph(),
                                old.rules()));
                c.commit();
            }
            var r = new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid());
            assertEquals("REJECTED", r.state());
            assertEquals(0, count(p, "evolution_estimate_revisions"));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("future"))) {
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.executeUpdate(
                        "INSERT INTO"
                            + " evolution_estimate_epochs(id,graph_id,graph_version,started_at,evidence_through)"
                            + " VALUES('future','java-debugging-concurrency',1,'2030-01-01T00:00:00Z',0)");
            }
            assertEquals(
                    "REJECTED",
                    new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid()).state());
            assertEquals(0, count(p, "evolution_estimate_revisions"));
        }
    }

    @Test
    void concurrentCommandsCannotPartiallyPublish() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.function.Consumer<PracticeSkillsService.Boundary> hook =
                    b -> {
                        if (b == PracticeSkillsService.Boundary.CAPTURED)
                            try {
                                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                    };
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a =
                        pool.submit(
                                () ->
                                        new PracticeSkillsService(p, CLOCK, hook)
                                                .reconcile(uuid(), uuid()));
                var b =
                        pool.submit(
                                () ->
                                        new PracticeSkillsService(p, CLOCK, hook)
                                                .reconcile(uuid(), uuid()));
                for (var f : List.of(a, b)) {
                    var r = f.get();
                    assertTrue(Set.of("PUBLISHED", "REJECTED").contains(r.state()));
                    assertEquals(r.state().equals("PUBLISHED") ? 4 : 0, r.skills().size());
                }
            }
            assertTrue(count(p, "evolution_estimate_revisions") <= 4);
        }
    }

    @Test
    void genuineMutantFailuresRemainContradictionsWithoutUnrelatedWrites() throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseFile(p.database());
            var aid =
                    com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance
                            .NONE_DECLARED;
            evaluation("counter-repair", -4, aid);
            evaluation("remaining-capacity", -3, aid);
            evaluation("atomic-boundary-drill", -2, aid, true);
            evaluation("atomic-boundary-drill", -1, aid, true);
            var before = database(p, true);
            var r = new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid());
            var e = atomic(r).estimate();
            assertEquals(MasteryTypes.Band.DEVELOPING, e.band());
            assertTrue(e.contradicted());
            assertTrue(e.regressionCap());
            assertEquals(before, database(p, true));
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
    }

    @Test
    void incompatibleGraphMissingMappingAndOversizedMetadataRejectWithoutPartialPublication()
            throws Exception {
        for (String mode : List.of("graph", "mapping", "oversized"))
            try (var p = ExperimentalProfile.openLearning(dir.resolve(mode))) {
                try (var c = p.connection(false)) {
                    if (mode.equals("graph")) {
                        c.setAutoCommit(false);
                        var old = JavaDebuggingSkillSeed.graph();
                        new com.codefit.repository.SkillCatalogRepository()
                                .publishGraph(
                                        c,
                                        new com.codefit.evolution.SkillGraph(
                                                new com.codefit.evolution.GraphRef(
                                                        old.ref().graphId(), 2),
                                                old.definitions(),
                                                old.prerequisites()));
                        c.commit();
                    } else if (mode.equals("oversized")) {
                        try (var q =
                                c.prepareStatement(
                                        "INSERT INTO evolution_skill_definitions"
                                            + " VALUES('oversized',1,'other',?,'fixture','ACTIVE',0)")) {
                            q.setString(1, "x".repeat(25000));
                            q.executeUpdate();
                        }
                    } else {
                        // Deliberately damaged local metadata: publication must fail closed, not
                        // fall back.
                        var triggers = new ArrayList<String>();
                        try (var q = c.createStatement();
                                var r =
                                        q.executeQuery(
                                                "SELECT name FROM sqlite_master WHERE"
                                                    + " type='trigger' AND tbl_name IN"
                                                    + " ('evolution_evidence_mappings','evolution_evidence_mapping_rules')")) {
                            while (r.next()) triggers.add(r.getString(1));
                        }
                        try (var q = c.createStatement()) {
                            for (var t : triggers) q.execute("DROP TRIGGER [" + t + "]");
                            q.executeUpdate("DELETE FROM evolution_evidence_mapping_rules");
                            q.executeUpdate("DELETE FROM evolution_evidence_mappings");
                        }
                    }
                }
                var before = database(p, true);
                assertEquals(
                        "REJECTED",
                        new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid()).state());
                assertEquals(0, count(p, "evolution_estimate_revisions"));
                assertEquals(before, database(p, true));
            }
    }

    @Test
    void clockRollbackCannotPublishAnEarlierInterpretation() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid());
            var before = database(p, true);
            var result =
                    new PracticeSkillsService(p, Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC))
                            .reconcile(uuid(), uuid());
            assertEquals("REJECTED", result.state());
            assertEquals(4, count(p, "evolution_estimate_revisions"));
            assertEquals(before, database(p, true));
        }
    }
}
