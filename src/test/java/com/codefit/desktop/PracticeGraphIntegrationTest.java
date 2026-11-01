package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.repository.*;
import com.codefit.repository.GraphSelectionRepository.Command;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PracticeGraphIntegrationTest {
    @TempDir Path dir;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    static final GraphRef V2 = new GraphRef("java-debugging-concurrency", 2),
            OTHER = new GraphRef("synthetic.engineering", 1);

    String uuid() {
        return UUID.randomUUID().toString();
    }

    Command command(ExperimentalProfile p, GraphRef target, long revision) {
        return new Command(uuid(), p.id(), uuid(), PracticeGraphService.TRACK, target, revision);
    }

    void publish(ExperimentalProfile p) throws Exception {
        try (var c = p.connection(false)) {
            c.setAutoCommit(false);
            var repo = new SkillCatalogRepository();
            var pilot = JavaDebuggingSkillSeed.graph();
            repo.publishGraph(c, new SkillGraph(V2, pilot.definitions(), pilot.prerequisites()));
            var skill =
                    new SkillDefinition(
                            new SkillRef("synthetic.behavior", 1),
                            "software-engineering",
                            "Synthetic fixture",
                            "Test-only independent graph",
                            List.of("Preserves a test invariant"),
                            SkillLifecycle.ACTIVE);
            repo.publishDefinition(c, skill);
            repo.publishGraph(c, new SkillGraph(OTHER, List.of(skill), List.of()));
            c.commit();
        }
    }

    @Test
    void publicationDoesNotUpgradeAndSelectionSurvivesRestartWithoutChangingHistory()
            throws Exception {
        String profile;
        Command forward;
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            profile = p.id();
            publish(p);
            var service = new PracticeGraphService(p, CLOCK);
            var before = new PracticeSkillsIntegrationTest().database(p, false);
            assertEquals(JavaDebuggingSkillSeed.GRAPH, service.read(null).selection().graph());
            assertEquals(3, service.read(OTHER).graphs().size());
            assertEquals(before, new PracticeSkillsIntegrationTest().database(p, false));
            assertEquals(
                    "PUBLISHED",
                    new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid()).state());
            var histories = new PracticeSkillsIntegrationTest().database(p, false);
            forward = command(p, V2, 0);
            assertEquals("SELECTED", service.choose(forward).state());
            assertEquals(
                    "UNSUPPORTED_GRAPH",
                    service.read(V2).graphs().stream()
                            .filter(g -> g.ref().equals(V2))
                            .findFirst()
                            .orElseThrow()
                            .compatibility()
                            .estimator());
            assertEquals(
                    "REJECTED",
                    new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid()).state());
            var after = new PracticeSkillsIntegrationTest().database(p, false);
            assertEquals(
                    histories.get("evolution_estimate_revisions"),
                    after.get("evolution_estimate_revisions"));
            assertEquals(
                    histories.get("evolution_evidence_observations"),
                    after.get("evolution_evidence_observations"));
            assertEquals(service.status(forward), service.choose(forward));
            assertEquals(after, new PracticeSkillsIntegrationTest().database(p, false));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.choose(
                                    new Command(
                                            forward.id(),
                                            p.id(),
                                            forward.origin(),
                                            forward.track(),
                                            OTHER,
                                            0)));
        }
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            assertEquals(profile, p.id());
            var service = new PracticeGraphService(p, CLOCK);
            assertEquals(V2, service.read(null).selection().graph());
            assertNotNull(service.status(forward));
            assertEquals(
                    "SELECTED",
                    service.choose(command(p, JavaDebuggingSkillSeed.GRAPH, 1)).state());
            assertEquals(
                    "PUBLISHED",
                    new PracticeSkillsService(p, CLOCK).reconcile(uuid(), uuid()).state());
        }
    }

    @Test
    void retirementIsExplicitAndHistoricalGraphRemainsReadable() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            publish(p);
            var service = new PracticeGraphService(p, CLOCK);
            service.choose(command(p, V2, 0));
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                new GraphSelectionRepository().lifecycle(c, V2, 0, "RETIRED", OTHER);
                c.commit();
            }
            var view = service.read(V2);
            assertEquals(V2, view.selection().graph());
            assertEquals("GRAPH_RETIRED", view.selectionStatus());
            assertNotNull(view.detail());
            assertEquals("REJECTED", service.choose(command(p, V2, 1)).state());
            assertEquals("SELECTED", service.choose(command(p, OTHER, 1)).state());
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new GraphSelectionRepository().lifecycle(c, V2, 0, "ACTIVE", null));
                c.rollback();
            }
        }
    }

    @Test
    void staleSelectionAndMissingVersionFailClosed() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            publish(p);
            var service = new PracticeGraphService(p, CLOCK);
            assertEquals("SELECTED", service.choose(command(p, V2, 0)).state());
            assertEquals("SELECTION_CONFLICT", service.choose(command(p, OTHER, 0)).reason());
            assertEquals(V2, service.read(null).selection().graph());
            assertEquals(
                    "GRAPH_MISSING",
                    service.choose(command(p, new GraphRef("missing.graph", 1), 1)).reason());
            assertNull(service.read(new GraphRef("missing.graph", 1)).detail());
        }
    }

    @Test
    void commitBoundaryRecoveryUsesOriginalReceipt() throws Exception {
        for (var boundary : PracticeGraphService.Boundary.values())
            try (var p = ExperimentalProfile.openLearning(dir.resolve(boundary.name()))) {
                publish(p);
                var cmd = command(p, V2, 0);
                var service =
                        new PracticeGraphService(
                                p,
                                CLOCK,
                                b -> {
                                    if (b == boundary)
                                        throw new IllegalStateException("simulated interruption");
                                });
                assertThrows(IllegalStateException.class, () -> service.choose(cmd));
                var recovered = new PracticeGraphService(p, CLOCK);
                assertEquals(
                        boundary == PracticeGraphService.Boundary.AFTER_COMMIT,
                        recovered.status(cmd) != null);
                assertEquals("SELECTED", recovered.choose(cmd).state());
                assertEquals(1, recovered.read(null).selection().revision());
            }
    }

    @Test
    void concurrentCommandsCannotBothAdvanceOneRevision() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("concurrent"))) {
            publish(p);
            var a = command(p, V2, 0);
            var b = command(p, OTHER, 0);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return new PracticeGraphService(p, CLOCK).choose(a);
                                });
                var second =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return new PracticeGraphService(p, CLOCK).choose(b);
                                });
                start.countDown();
                var results =
                        List.of(
                                first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                                second.get(15, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(r -> r.state().equals("SELECTED")).count());
                assertEquals(
                        1,
                        results.stream()
                                .filter(r -> r.reason().equals("SELECTION_CONFLICT"))
                                .count());
                assertEquals(
                        1, new PracticeGraphService(p, CLOCK).read(null).selection().revision());
            }
        }
    }

    @Test
    void selectionGatesNewOpeningsButOriginalExposureCanResume() throws Exception {
        String previous = com.codefit.config.DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(dir.resolve("presentations"))) {
            publish(p);
            var app = new PracticeApplicationService(p, CLOCK);
            var candidate = app.list().getFirst();
            var quest =
                    new com.codefit.evolution.quest.CatalogRef(
                            candidate.questId(), candidate.questVersion());
            var challenge =
                    new com.codefit.evolution.quest.CatalogRef(
                            candidate.challengeId(), candidate.challengeVersion());
            String id = uuid();
            var original = app.open(id, quest, challenge);
            new PracticeGraphService(p, CLOCK).choose(command(p, V2, 0));
            assertTrue(app.list().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> app.open(uuid(), quest, challenge));
            assertEquals(original, app.open(id, quest, challenge));
        } finally {
            com.codefit.config.DatabaseConfig.useDatabaseUrl(previous);
        }
    }
}
