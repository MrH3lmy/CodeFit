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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class PracticeDebriefIntegrationTest {
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

    Map<String, String> database(ExperimentalProfile p) throws Exception {
        var result = new TreeMap<String, String>();
        try (var c = p.connection(true);
                var tables = c.createStatement();
                var rows =
                        tables.executeQuery(
                                "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY"
                                        + " name")) {
            while (rows.next()) {
                String name = rows.getString(1);
                var content = new StringBuilder();
                try (var q = c.createStatement();
                        var r =
                                q.executeQuery(
                                        "SELECT * FROM \""
                                                + name.replace("\"", "\"\"")
                                                + "\" ORDER BY rowid")) {
                    while (r.next())
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                            byte[] value = r.getBytes(i);
                            content.append(value == null ? "NULL" : HexFormat.of().formatHex(value))
                                    .append('|');
                        }
                }
                result.put(name, content.toString());
            }
        }
        return result;
    }

    String json(PracticeDebrief.Projection d) throws Exception {
        return new String(
                PracticeProtocol.message(g -> PracticeDebriefProtocol.write(g, d)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {
                "PASSED",
                "FAILED",
                "PARTIAL",
                "COMPILE_ERROR",
                "UNSUPPORTED",
                "TOOL_UNAVAILABLE",
                "CANCELLED",
                "INCONCLUSIVE"
            })
    void readsRealFinalizedRecordsWithoutAnyMutationOrPrivateFields(Outcome outcome)
            throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var draft = valid(p, presentation);
            e.prepare(command, presentation, draft.revision(), "UNKNOWN");
            assertThrows(Exception.class, () -> e.debrief(command, presentation));
            EvaluationTestFixtures.finalizeProjectionFixture(command, outcome);
            var before = database(p);
            var d = e.debrief(command, presentation);
            assertEquals(outcome, d.evaluation().view().outcome());
            assertEquals("RECORDED", d.evidence().status());
            assertEquals("LOCAL_CHECK", d.evidence().provenance());
            assertEquals("EXACT", d.evidence().mappingStatus());
            assertEquals(1, d.evidence().revision());
            assertFalse(d.skills().isEmpty());
            assertTrue(d.skills().stream().allMatch(s -> s.estimate().status().equals("missing")));
            for (int n = 0; n < 3; n++) e.debrief(command, presentation);
            assertEquals(before, database(p));
            String wire = json(d);
            for (String secret :
                    List.of(
                            "PRIVATE_DIAGNOSTIC_SENTINEL",
                            "PRIVATE_REPORT_SENTINEL",
                            "Synthetic reflection",
                            reference(),
                            "expectedStdout",
                            "stdin",
                            "diagnostics",
                            "artifact\"")) assertFalse(wire.contains(secret), secret);
            assertTrue(wire.contains("NOT_EVALUATED"));
            assertEquals(0, count(p, "evolution_estimate_revisions"));
            assertEquals(0, count(p, "evolution_adaptation_decisions"));
        }
    }

    @Test
    void actualReferencePassRestartAndLostReadNeverExecuteAgain() throws Exception {
        String command = id(), presentation;
        Map<String, String> before;
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            presentation = open(p);
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            EvaluationTestFixtures.loseFirstResponse(Clock.systemUTC())
                                    .resume(command));
            assertEquals(
                    Outcome.PASSED, e.debrief(command, presentation).evaluation().view().outcome());
            before = database(p);
        }
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            new PracticeApplicationService(p, Clock.systemUTC());
            e.debrief(command, presentation); // response deliberately ignored
            var d = e.debrief(command, presentation);
            assertEquals(Outcome.PASSED, d.evaluation().view().outcome());
            assertEquals(before, database(p));
        }
    }

    @Test
    void correctedAndWithdrawnSourcesNeverResurrectOriginalPositiveEvidence() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.PASSED);
            var original = e.debrief(command, presentation);
            new ChallengeEvaluationCoordinator().invalidate(command, id(), false);
            var corrected = e.debrief(command, presentation);
            assertTrue(corrected.corrected());
            assertEquals(2, corrected.evaluationRevision());
            assertEquals(Outcome.INCONCLUSIVE, corrected.evaluation().view().outcome());
            assertEquals(original.evidence().id(), corrected.evidence().previousId());
            new ChallengeEvaluationCoordinator().invalidate(command, id(), true);
            var withdrawn = e.debrief(command, presentation);
            assertTrue(withdrawn.evaluation().view().withdrawn());
            assertEquals("RETRACTED", withdrawn.evidence().availability());
            assertEquals("UNAVAILABLE", withdrawn.evidence().outcome());
        }
    }

    @Test
    void savedInterpretationsRemainHistoricalAndReadsDoNotPublishNewRevision() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.PASSED);
            new com.codefit.evolution.mastery.MasteryEstimateService().pilot(Instant.now());
            var before = database(p);
            var result = e.debrief(command, presentation);
            assertTrue(
                    result.skills().stream()
                            .allMatch(s -> s.estimate().status().equals("historical")));
            assertEquals(before, database(p));
            new ChallengeEvaluationCoordinator().invalidate(command, id(), true);
            assertTrue(
                    e.debrief(command, presentation).skills().stream()
                            .anyMatch(s -> s.estimate().warnings().contains("EVIDENCE_WITHDRAWN")));
        }
    }

    @Test
    void wrongPresentationUnfinishedAndForeignCommandsFailClosed() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "UNKNOWN");
            assertThrows(Exception.class, () -> e.debrief(command, presentation));
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.CANCELLED);
            assertThrows(Exception.class, () -> e.debrief(command, "desktop-practice-" + id()));
            assertThrows(Exception.class, () -> e.debrief(id(), presentation));
        }
    }

    @Test
    void staleSavedEstimateAndNewGraphNeverBecomeCurrentSupport() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.PASSED);
            Instant future = Instant.now().plus(Duration.ofDays(40));
            new com.codefit.evolution.mastery.MasteryEstimateService().pilot(future);
            var original = com.codefit.config.JavaDebuggingSkillSeed.graph();
            new com.codefit.repository.SkillCatalogRepository()
                    .publishGraph(
                            new com.codefit.evolution.SkillGraph(
                                    new com.codefit.evolution.GraphRef(original.ref().graphId(), 2),
                                    original.definitions(),
                                    original.prerequisites()));
            try (var historical =
                    new PracticeEvaluationService(
                            p, Clock.fixed(future.plusSeconds(1), ZoneOffset.UTC))) {
                var result = historical.debrief(command, presentation);
                assertEquals(1, result.graphVersion());
                assertTrue(
                        result.skills().stream()
                                .allMatch(x -> x.estimate().status().equals("historical")));
                assertTrue(
                        result.skills().stream()
                                .anyMatch(x -> "STALE".equals(x.estimate().freshness())));
                assertTrue(json(result).contains("NOT_EVALUATED"));
            }
            // A future saved interpretation is not silently substituted into today's read.
            assertTrue(
                    e.debrief(command, presentation).skills().stream()
                            .allMatch(x -> x.estimate().status().equals("unavailable")));
        }
    }

    @Test
    void missingInterpretationMappingAndCorruptEvidenceFailClosed() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.PASSED);
            var first = e.debrief(command, presentation);
            // Deliberately damaged isolated DB; production immutability guards remain unchanged.
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                var triggers = new ArrayList<String>();
                try (var rows =
                        q.executeQuery(
                                "SELECT name FROM sqlite_schema WHERE type='trigger' AND tbl_name"
                                    + " IN ('evolution_evidence_interpretations','evolution_evidence_observations')")) {
                    while (rows.next()) triggers.add(rows.getString(1));
                }
                for (var t : triggers) q.execute("DROP TRIGGER \"" + t + "\"");
                q.executeUpdate("DELETE FROM evolution_evidence_interpretations");
            }
            assertEquals(
                    "UNAVAILABLE", e.debrief(command, presentation).evidence().mappingStatus());
            try (var c = p.connection(false);
                    var q =
                            c.prepareStatement(
                                    "UPDATE evolution_evidence_observations SET payload=? WHERE"
                                            + " evidence_id=?")) {
                q.setBytes(1, new byte[] {1, 2, 3});
                q.setString(2, first.evidence().id());
                q.executeUpdate();
            }
            assertThrows(Exception.class, () -> e.debrief(command, presentation));
            assertEquals(Stage.FINALIZED, e.status(command, presentation).view().stage());
        }
    }

    @Test
    void supersededMappingDoesNotMasqueradeAsCurrentInterpretation() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var e = new PracticeEvaluationService(p, Clock.systemUTC())) {
            String presentation = open(p), command = id();
            var d = valid(p, presentation);
            e.prepare(command, presentation, d.revision(), "NONE_DECLARED");
            EvaluationTestFixtures.finalizeProjectionFixture(command, Outcome.PASSED);
            var original = e.debrief(command, presentation);
            var mapping =
                    new DurableEvaluationRepository()
                            .evaluationInput(command)
                            .orElseThrow()
                            .mapping();
            new com.codefit.repository.EvidenceMappingRepository()
                    .publish(
                            new com.codefit.evolution.evidence.EvidenceMapping(
                                    new com.codefit.evolution.evidence.EvidenceMappingRef(
                                            mapping.ref().mappingId(), 2),
                                    mapping.graph(),
                                    List.of()));
            var before = database(p);
            var result = e.debrief(command, presentation);
            assertEquals("SUPERSEDED", result.evidence().mappingStatus());
            assertEquals(1, result.evidence().mappingVersion());
            assertEquals(original.evidence().id(), result.evidence().id());
            assertEquals(before, database(p));
        }
    }
}
