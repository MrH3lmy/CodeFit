package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.mastery.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PracticeAnalyticsTest {
    @TempDir Path dir;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);

    static Map<String, String> hashes(ExperimentalProfile p) throws Exception {
        var helper = new PracticeSkillsIntegrationTest();
        var result = new TreeMap<String, String>();
        for (var e : helper.database(p, false).entrySet())
            result.put(
                    e.getKey(),
                    com.codefit.evolution.quest.CatalogCodec.digest(
                            e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return result;
    }

    @Test
    void emptyReadsDoNotWriteAndGraphIsExact() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, CLOCK, 0, "");
            assertEquals(JavaDebuggingSkillSeed.graph(), v.graph());
            assertTrue(v.revisions().isEmpty());
            assertTrue(v.events().isEmpty());
            assertEquals(v, PracticeAnalytics.read(p, CLOCK, 0, ""));
            assertEquals(before, hashes(p));
        }
    }

    @Test
    void realHistoryCorrectionsCoverageAndWarningsAreExactAndReadOnly() throws Exception {
        String old = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseUrl("jdbc:sqlite:" + dir.resolve("p/codefit.db"));
            AnalyticsFixture.seed(p);
            // Repository ingestion timestamps use UTC now; freeze capture after ingestion.
            Clock captured = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, captured, 0, "");
            assertEquals(16, v.revisions().size());
            assertEquals(4, v.events().size());
            assertTrue(v.events().stream().anyMatch(e -> e.change().equals("RETRACTION")));
            assertEquals(2, v.events().stream().filter(PracticeAnalytics.Event::head).count());
            var atomic =
                    v.revisions().stream()
                            .filter(r -> r.skill().equals("java.concurrency.atomic-update"))
                            .toList();
            assertEquals(4, atomic.size());
            assertTrue(
                    atomic.stream()
                            .anyMatch(r -> r.estimate().band() == MasteryTypes.Band.INDEPENDENT));
            assertTrue(atomic.stream().anyMatch(r -> r.warnings().contains("EVIDENCE_WITHDRAWN")));
            try (var c = p.connection(true)) {
                c.setAutoCommit(false);
                for (var r : v.revisions()) {
                    var saved = new EstimateRevisionRepository().find(c, r.id()).orElseThrow();
                    assertEquals(saved.estimate(), r.estimate());
                    assertEquals(
                            saved.estimate().supportedBehaviors().contains(0),
                            PracticeAnalytics.coverage(r.estimate(), 0).equals("SUPPORTED"));
                }
            }
            String json =
                    new String(
                            PracticeProtocol.message(g -> PracticeAnalytics.write(g, v)),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(json.contains("public class"));
            assertFalse(json.contains("sourceCode"));
            assertFalse(json.contains("expectedOutput"));
            assertEquals(v, PracticeAnalytics.read(p, captured, 0, ""));
            assertEquals(before, hashes(p));
        } finally {
            DatabaseConfig.useDatabaseUrl(old);
        }
    }

    @Test
    void savedStaleAndContradictedInterpretationsRemainExact() throws Exception {
        String old = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseFile(p.database());
            AnalyticsFixture.evaluate("counter-repair", 1);
            AnalyticsFixture.evaluate("remaining-capacity", 2);
            AnalyticsFixture.reconcile(p, 40);
            AnalyticsFixture.evaluate("atomic-boundary-drill", 41, true);
            AnalyticsFixture.evaluate("atomic-boundary-drill", 42, true);
            AnalyticsFixture.reconcile(p, 43);
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, Clock.systemUTC(), 0, "");
            var atomic =
                    v.revisions().stream()
                            .filter(r -> r.skill().equals("java.concurrency.atomic-update"))
                            .toList();
            assertTrue(
                    atomic.stream()
                            .anyMatch(
                                    r -> r.estimate().freshness() == MasteryTypes.Freshness.STALE));
            var contradicted =
                    atomic.stream()
                            .filter(r -> r.estimate().contradicted())
                            .findFirst()
                            .orElseThrow();
            assertEquals(MasteryTypes.Band.DEVELOPING, contradicted.estimate().band());
            assertEquals("CONTRADICTED", PracticeAnalytics.coverage(contradicted.estimate(), 0));
            assertEquals(before, hashes(p));
        } finally {
            DatabaseConfig.useDatabaseUrl(old);
        }
    }

    @Test
    void paginationIsBoundedAndChangesInvalidatePriorSnapshot() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            for (int i = 0; i < 6; i++) AnalyticsFixture.reconcile(p, i);
            var v = PracticeAnalytics.read(p, CLOCK, 0, "");
            assertTrue(v.more());
            var next = PracticeAnalytics.read(p, CLOCK, 1, v.snapshot());
            assertEquals(8, next.revisions().size());
            assertTrue(
                    Collections.disjoint(
                            v.revisions().stream().map(PracticeAnalytics.Revision::id).toList(),
                            next.revisions().stream()
                                    .map(PracticeAnalytics.Revision::id)
                                    .toList()));
            AnalyticsFixture.reconcile(p, 7);
            assertThrows(
                    SQLException.class, () -> PracticeAnalytics.read(p, CLOCK, 1, v.snapshot()));
        }
    }

    @Test
    void oldPolicyAndGraphAreVisibleAsIncompatibleNotFabricatedBands() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            AnalyticsFixture.reconcile(p, 0);
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                c.setAutoCommit(false);
                new com.codefit.repository.SkillCatalogRepository()
                        .publishGraph(
                                c,
                                new com.codefit.evolution.SkillGraph(
                                        new com.codefit.evolution.GraphRef(
                                                "java-debugging-concurrency", 2),
                                        JavaDebuggingSkillSeed.graph().definitions(),
                                        JavaDebuggingSkillSeed.graph().prerequisites()));
                q.execute("DROP TRIGGER evolution_estimate_revisions_immutable_update");
                q.executeUpdate(
                        "UPDATE evolution_estimate_revisions SET policy='future-v2',graph_version=2"
                                + " WHERE revision=(SELECT MIN(revision) FROM"
                                + " evolution_estimate_revisions)");
                c.commit();
            }
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, CLOCK, 0, "");
            assertTrue(
                    v.revisions().stream()
                            .anyMatch(
                                    r ->
                                            r.status().equals("INCOMPATIBLE")
                                                    && r.estimate() == null));
            assertEquals(before, hashes(p));
        }
    }

    @Test
    void oversizedMetadataFailsBeforeDeserialization() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            AnalyticsFixture.reconcile(p, 0);
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DROP TRIGGER evolution_estimate_revisions_immutable_update");
                q.executeUpdate("UPDATE evolution_estimate_revisions SET inputs=zeroblob(1048577)");
            }
            var before = hashes(p);
            assertThrows(SQLException.class, () -> PracticeAnalytics.read(p, CLOCK, 0, ""));
            assertEquals(before, hashes(p));
        }
    }

    @Test
    void protocolRejectsHostileQueries() throws Exception {
        String base =
                "{\"v\":1,\"id\":\""
                        + UUID.randomUUID()
                        + "\",\"method\":\"practice.analytics.read\",\"profileId\":\""
                        + UUID.randomUUID()
                        + "\",\"sessionId\":\""
                        + UUID.randomUUID()
                        + "\",\"page\":0,\"snapshot\":\"\"}";
        assertEquals(0, PracticeProtocol.parse(base.getBytes()).analyticsPage());
        for (String invalid :
                List.of(
                        base.replace("\"page\":0", "\"page\":-1"),
                        base.replace("\"page\":0", "\"page\":64"),
                        base.replace("\"page\":0", "\"page\":1"),
                        base.replace("\"page\":0", "\"sql\":\"SELECT *\",\"page\":0")))
            assertThrows(Exception.class, () -> PracticeProtocol.parse(invalid.getBytes()));
    }

    @Test
    void resetIsExplicitAndHistoricalInputNeverRewritten() throws Exception {
        String old = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            DatabaseConfig.useDatabaseUrl("jdbc:sqlite:" + dir.resolve("p/codefit.db"));
            AnalyticsFixture.reconcile(p, 0);
            new EstimateRevisionRepository()
                    .reset(
                            JavaDebuggingSkillSeed.GRAPH,
                            "analytics-reset",
                            AnalyticsFixture.clock(1));
            AnalyticsFixture.reconcile(p, 2);
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, CLOCK, 0, "");
            assertEquals(
                    Set.of("initial", "analytics-reset"),
                    new HashSet<>(
                            v.revisions().stream()
                                    .map(PracticeAnalytics.Revision::epoch)
                                    .toList()));
            assertTrue(
                    v.revisions().stream()
                            .filter(r -> r.epoch().equals("initial"))
                            .allMatch(r -> r.warnings().contains("EPOCH_CHANGED")));
            assertEquals(before, hashes(p));
        } finally {
            DatabaseConfig.useDatabaseUrl(old);
        }
    }

    @Test
    void corruptRevisionRemainsUnavailableAndCannotBecomeUnknownOrSupported() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            AnalyticsFixture.reconcile(p, 0);
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DROP TRIGGER evolution_estimate_revisions_immutable_update");
                q.executeUpdate(
                        "UPDATE evolution_estimate_revisions SET inputs=x'0000' WHERE revision=1");
            }
            var before = hashes(p);
            var v = PracticeAnalytics.read(p, CLOCK, 0, "");
            assertEquals(
                    "UNAVAILABLE",
                    v.revisions().stream()
                            .filter(r -> r.id() == 1)
                            .findFirst()
                            .orElseThrow()
                            .status());
            assertNull(
                    v.revisions().stream()
                            .filter(r -> r.id() == 1)
                            .findFirst()
                            .orElseThrow()
                            .estimate());
            assertEquals(before, hashes(p));
        }
    }

    @Test
    void libraryProfileAndFutureTimestampFailClosed() throws Exception {
        try (var p = ExperimentalProfile.open(dir.resolve("library"))) {
            assertThrows(
                    IllegalArgumentException.class, () -> PracticeAnalytics.read(p, CLOCK, 0, ""));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            AnalyticsFixture.reconcile(p, 0);
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DROP TRIGGER evolution_estimate_revisions_immutable_update");
                q.executeUpdate(
                        "UPDATE evolution_estimate_revisions SET as_of='2099-01-01T00:00:00Z'");
            }
            var before = hashes(p);
            assertThrows(SQLException.class, () -> PracticeAnalytics.read(p, CLOCK, 0, ""));
            assertEquals(before, hashes(p));
        }
    }

    @Test
    void missingMetadataAndMalformedGraphNeverReturnPartialCharts() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute(
                        "ALTER TABLE evolution_evidence_observations RENAME TO"
                                + " absent_observations");
            }
            var before = hashes(p);
            assertThrows(SQLException.class, () -> PracticeAnalytics.read(p, CLOCK, 0, ""));
            assertEquals(before, hashes(p));
        }
    }
}
