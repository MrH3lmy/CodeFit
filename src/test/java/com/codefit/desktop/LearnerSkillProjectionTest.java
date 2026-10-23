package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.ChallengePresentationService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class LearnerSkillProjectionTest {
    @TempDir Path directory;
    static final Instant CAPTURED = Instant.parse("2026-10-01T12:00:00Z");

    Path fixture(String mode) throws Exception {
        Path p = directory.resolve(UUID.randomUUID() + ".db");
        AscensionFixture.create(p, mode);
        return p;
    }

    LearnerSkillProjection.Projection read(Path path) throws Exception {
        try (var c = LearnerSnapshot.openReadOnly(path, false)) {
            c.setAutoCommit(false);
            var value =
                    LearnerSkillProjection.read(
                            c, "a".repeat(64), UUID.randomUUID().toString(), CAPTURED);
            c.rollback();
            return value;
        }
    }

    LearnerSkillProjection.Estimate atomic(LearnerSkillProjection.Projection p) {
        return p.nodes().stream()
                .filter(n -> n.ref().id().equals("java.concurrency.atomic-update"))
                .findFirst()
                .orElseThrow()
                .estimate();
    }

    @Test
    void newLearnerGetsExactPublishedDefinitionsAndNoFabricatedEstimate() throws Exception {
        var p = read(fixture("new"));
        assertEquals("available", p.status());
        assertEquals(4, p.nodes().size());
        assertEquals(3, p.edges().size());
        assertEquals("java-debugging-concurrency", p.graph().id());
        assertEquals(1, p.graph().version());
        assertTrue(
                p.nodes().stream()
                        .allMatch(
                                n ->
                                        n.estimate().status().equals("missing")
                                                && n.estimate().band() == null));
        assertEquals("java.debugging.reproduce", p.nodes().getFirst().ref().id());
        assertTrue(
                p.edges().stream()
                        .anyMatch(
                                e ->
                                        e.prerequisite().id().equals("java.debugging.reproduce")
                                                && e.dependent()
                                                        .id()
                                                        .equals("java.concurrency.atomic-update")));
    }

    @ParameterizedTest
    @CsvSource({
        "supported,INDEPENDENT,CURRENT,false",
        "insufficient,DEVELOPING,CURRENT,false",
        "stale,INDEPENDENT,STALE,false",
        "contradictory,INDEPENDENT,CURRENT,true"
    })
    void savedOrdinalStatesRetainTheirOriginalTime(
            String mode, String band, String freshness, boolean contradicted) throws Exception {
        var p = read(fixture(mode));
        var e = atomic(p);
        assertEquals("historical", e.status(), e.reason());
        assertEquals(band, e.band());
        assertEquals(freshness, e.freshness());
        assertEquals(contradicted, e.contradicted());
        assertTrue(e.asOf().isBefore(CAPTURED));
        assertEquals("ordinal-v1", e.policyVersion());
        assertTrue(e.warnings().isEmpty(), e.warnings().toString());
        String wire =
                new String(
                        LearnerProtocol.result(UUID.randomUUID().toString(), p),
                        StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"currentSupport\":\"NOT_EVALUATED\""));
        for (String forbidden :
                List.of(
                        "PRIVATE-",
                        "fixture-submission",
                        "fixture-presentation",
                        "fixture-functional",
                        "artifactSha",
                        "payload",
                        "inputs",
                        "head_id")) assertFalse(wire.contains(forbidden), forbidden);
    }

    @ParameterizedTest
    @CsvSource({
        "withdrawn,EVIDENCE_WITHDRAWN",
        "revised,EVIDENCE_REVISED",
        "epoch,EPOCH_CHANGED",
        "mapping,MAPPING_CHANGED"
    })
    void lifecycleWarningsNeverTurnHistoricalSupportIntoCurrentEligibility(
            String mode, String warning) throws Exception {
        var e = atomic(read(fixture(mode)));
        assertEquals("historical", e.status());
        assertEquals("INDEPENDENT", e.band());
        assertTrue(e.warnings().contains(warning), e.warnings().toString());
    }

    @ParameterizedTest
    @CsvSource({
        "outdated,GRAPH_MISMATCH",
        "unknown-policy,POLICY_UNAVAILABLE",
        "future,REVISION_INVALID"
    })
    void invalidLatestRevisionNeverFallsBackToOlderPositiveHistory(String mode, String reason)
            throws Exception {
        var e = atomic(read(fixture(mode)));
        assertEquals("unavailable", e.status());
        assertEquals(reason, e.reason());
        assertNull(e.band());
        assertEquals(0, e.contributionCount());
    }

    @Test
    void sourceAndPrivateSnapshotAreByteIdenticalAfterReadsAndGlobalDatabaseIsUntouched()
            throws Exception {
        Path path = fixture("supported");
        byte[] source = Files.readAllBytes(path);
        String global = DatabaseConfig.currentDatabaseUrl();
        try (var snapshot =
                LearnerSnapshot.capture(
                        path, directory.resolve("backups"), UUID.randomUUID().toString())) {
            byte[] backup = Files.readAllBytes(snapshot.database());
            var first = snapshot.readSkills();
            assertEquals(first, snapshot.readSkills());
            snapshot.read();
            assertArrayEquals(source, Files.readAllBytes(path));
            assertArrayEquals(backup, Files.readAllBytes(snapshot.database()));
            assertEquals(global, DatabaseConfig.currentDatabaseUrl());
            try (var c = LearnerSnapshot.openReadOnly(snapshot.database(), false);
                    var s = c.createStatement()) {
                assertThrows(
                        SQLException.class,
                        () -> s.executeUpdate("UPDATE user_progress SET xp=999"));
            }
        }
    }

    @Test
    void newerGraphIsDisclosedAndBoundedLargeGraphIsComplete() throws Exception {
        var newer = read(fixture("newer-graph"));
        assertTrue(newer.newerGraphAvailable());
        assertEquals(1, newer.graph().version());
        var large = read(fixture("large"));
        assertEquals("available", large.status());
        assertEquals(32, large.nodes().size());
        var excessive = read(fixture("over-limit"));
        assertEquals("unavailable", excessive.status());
        assertEquals("LIMIT_EXCEEDED", excessive.reason());
        assertTrue(excessive.nodes().isEmpty());
    }

    @Test
    void missingOrMaliciousCatalogNeverUsesASeedFallback() throws Exception {
        Path p = fixture("new");
        execute(p, "ALTER TABLE evolution_skill_prerequisites RENAME TO private_edges");
        var missing = read(p);
        assertEquals("unavailable", missing.status());
        assertTrue(missing.nodes().isEmpty());
        execute(p, "CREATE VIEW evolution_skill_prerequisites AS SELECT * FROM private_edges");
        assertEquals("unavailable", read(p).status());
    }

    @Test
    void corruptOrOversizedLatestBlobIsUnavailableWithoutPositiveFallback() throws Exception {
        for (boolean oversized : List.of(false, true)) {
            Path p = fixture("supported");
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + p);
                    var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " evolution_estimate_revisions(input_sha,policy,graph_id,graph_version,skill_id,skill_version,epoch_id,as_of,inputs)"
                                        + " SELECT"
                                        + " ?,policy,graph_id,graph_version,skill_id,skill_version,epoch_id,as_of,?"
                                        + " FROM evolution_estimate_revisions LIMIT 1")) {
                s.setString(1, "b".repeat(64));
                s.setBytes(
                        2,
                        oversized
                                ? new byte[LearnerSkillProjection.MAX_INPUT_BYTES + 1]
                                : new byte[] {1, 2, 3});
                s.executeUpdate();
            }
            var e = atomic(read(p));
            assertEquals("unavailable", e.status());
            assertEquals(oversized ? "LIMIT_EXCEEDED" : "REVISION_INVALID", e.reason());
            assertNull(e.band());
        }
    }

    @Test
    void missingEvidenceIsExplicitAndDoesNotRevealItsPayload() throws Exception {
        Path p = fixture("supported");
        execute(p, "UPDATE evolution_evidence_sources SET head_id=NULL");
        var e = atomic(read(p));
        assertTrue(e.warnings().contains("EVIDENCE_MISSING"));
        assertEquals("historical", e.status());
    }

    @Test
    void projectionRequiresOneCallerReadTransaction() throws Exception {
        try (var c = LearnerSnapshot.openReadOnly(fixture("new"), false)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            LearnerSkillProjection.read(
                                    c, "a".repeat(64), UUID.randomUUID().toString(), CAPTURED));
        }
    }

    @Test
    void sourceChangesAfterCaptureCannotMixWithSavedGraphAndHistory() throws Exception {
        Path source = fixture("supported");
        try (var first =
                LearnerSnapshot.capture(
                        source, directory.resolve("first-store"), UUID.randomUUID().toString())) {
            var frozen = first.readSkills();
            assertTrue(atomic(frozen).warnings().isEmpty());
            execute(source, "UPDATE evolution_evidence_sources SET head_id=NULL");
            assertEquals(frozen, first.readSkills());
            try (var second =
                    LearnerSnapshot.capture(
                            source,
                            directory.resolve("second-store"),
                            UUID.randomUUID().toString())) {
                assertTrue(atomic(second.readSkills()).warnings().contains("EVIDENCE_MISSING"));
                assertNotEquals(first.sessionId(), second.sessionId());
            }
        }
    }

    @Test
    void exactRevisionFromRealEvaluationPipelineIsReplayedWithoutPublishingAgain()
            throws Exception {
        Path path = directory.resolve("real-evaluation.db");
        LearnerFixture.create(path);
        String old = DatabaseConfig.currentDatabaseUrl();
        try {
            DatabaseConfig.useDatabaseFile(path);
            var assets = new BundledAssets();
            int day = 0;
            for (String id : List.of("counter-repair", "atomic-boundary-drill")) {
                Clock clock =
                        Clock.fixed(
                                AscensionFixture.TIME.plus(Duration.ofDays(day++)), ZoneOffset.UTC);
                var spec =
                        JavaTrainingPack.challenges().stream()
                                .filter(s -> s.ref().id().equals(id))
                                .findFirst()
                                .orElseThrow();
                var quest =
                        JavaTrainingPack.quests().stream()
                                .filter(q -> q.challenges().contains(spec.ref()))
                                .findFirst()
                                .orElseThrow();
                String presentation = "actual-presentation-" + id;
                new ChallengePresentationService(new QuestCatalogRepository(), assets, clock)
                        .present(
                                new ChallengePresentation.Request(
                                        presentation,
                                        quest.ref(),
                                        spec.ref(),
                                        spec.cohort(),
                                        QuestTypes.Familiarity.DECLARED_UNFAMILIAR));
                String artifact =
                        assets.read(
                                spec.assets().stream()
                                        .filter(a -> a.kind() == QuestTypes.AssetKind.REFERENCE)
                                        .findFirst()
                                        .orElseThrow());
                var result =
                        EvaluationTestFixtures.service(clock)
                                .submit(
                                        new SubmissionRequest(
                                                "actual-command-" + id,
                                                presentation,
                                                artifact,
                                                EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                                                "PRIVATE-REFLECTION"));
                assertEquals(EvaluationTypes.Outcome.PASSED, result.outcome());
            }
            var saved =
                    new MasteryEstimateService()
                            .pilot(AscensionFixture.TIME.plus(Duration.ofDays(4))).stream()
                                    .filter(
                                            r ->
                                                    r.estimate()
                                                            .skill()
                                                            .equals(AscensionFixture.ATOMIC.ref()))
                                    .findFirst()
                                    .orElseThrow();
            byte[] before = Files.readAllBytes(path);
            var p = read(path);
            var e = atomic(p);
            assertEquals("historical", e.status(), e.reason());
            assertEquals(saved.id(), e.revision());
            assertEquals(saved.estimate().band().name(), e.band());
            assertEquals(saved.estimate().independentFamilies(), e.independentFamilies());
            assertTrue(e.warnings().isEmpty(), e.warnings().toString());
            String wire =
                    new String(
                            LearnerProtocol.result(UUID.randomUUID().toString(), p),
                            StandardCharsets.UTF_8);
            assertFalse(wire.contains("PRIVATE-REFLECTION"));
            assertFalse(wire.contains("actual-command"));
            assertArrayEquals(before, Files.readAllBytes(path));
        } finally {
            DatabaseConfig.useDatabaseUrl(old);
        }
    }

    static void execute(Path p, String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + p);
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
