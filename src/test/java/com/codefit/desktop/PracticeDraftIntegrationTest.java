package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.quest.CatalogRef;
import com.codefit.repository.JavaSolutionDraftRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class PracticeDraftIntegrationTest {
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
        var x = app.list().getFirst();
        return app.open(
                        id(),
                        new CatalogRef(x.questId(), x.questVersion()),
                        new CatalogRef(x.challengeId(), x.challengeVersion()))
                .receipt()
                .presentationId();
    }

    PracticeDraftService service(ExperimentalProfile p) {
        return new PracticeDraftService(p, Clock.systemUTC());
    }

    @Test
    void acknowledgedSaveAndPresentationSurviveRestartWithoutExposureOrLearningMutations()
            throws Exception {
        String presentation, command = id();
        long revision;
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            assertFalse(
                    d.source().contains("public class Main"), "trusted harness must be removed");
            var saved =
                    s.save(
                            command,
                            presentation,
                            d.revision(),
                            "class Exercise { /* synthetic */ }",
                            "synthetic reflection");
            assertEquals("SAVED", saved.outcome());
            revision = saved.revision();
            assertTrue(revision > d.revision());
            assertEquals(
                    saved,
                    s.save(
                            command,
                            presentation,
                            d.revision(),
                            "class Exercise { /* synthetic */ }",
                            "synthetic reflection"));
        }
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            new PracticeApplicationService(p, Clock.systemUTC());
            var s = service(p);
            var r = s.resume(presentation);
            assertEquals(revision, r.draft().revision());
            assertEquals("class Exercise { /* synthetic */ }", r.draft().source());
            assertEquals("synthetic reflection", r.draft().reflection());
            assertEquals(presentation, s.recent().getFirst().presentationId());
            assertEquals(revision, s.status(command, presentation).revision());
            try (var c = p.connection(true);
                    var q = c.createStatement()) {
                for (var table :
                        List.of(
                                "evolution_evidence_observations",
                                "evolution_estimate_revisions",
                                "evolution_adaptation_decisions",
                                "problem_attempts")) {
                    try (var rows = q.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
                try (var rows =
                        q.executeQuery("SELECT COUNT(*) FROM evolution_challenge_presentations")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void staleRevisionAndConflictingCommandNeverOverwrite() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            var presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            var command = id();
            s.save(command, presentation, d.revision(), "class Newer {}", "");
            assertEquals(
                    "CONFLICT",
                    s.save(id(), presentation, d.revision(), "class Stale {}", "").outcome());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> s.save(command, presentation, d.revision(), "class Changed {}", ""));
            assertEquals("class Newer {}", s.read(presentation).source());
        }
    }

    @Test
    void simultaneousUpdatesHaveExactlyOneWinner() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"));
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            var barrier = new CyclicBarrier(2);
            var tasks = new ArrayList<Future<PracticeDraftService.Saved>>();
            for (int i = 0; i < 2; i++) {
                int n = i;
                tasks.add(
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return s.save(
                                            id(),
                                            presentation,
                                            d.revision(),
                                            "class Source" + n + " {}",
                                            "");
                                }));
            }
            var outcomes = new HashSet<String>();
            for (var task : tasks) outcomes.add(task.get(10, TimeUnit.SECONDS).outcome());
            assertEquals(Set.of("SAVED", "CONFLICT"), outcomes);
        }
    }

    @Test
    void legacyRepositoryUpdateInvalidatesRevisionEvenAtSameTimestamp() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            var presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            try (var c = p.connection(false);
                    var q = c.createStatement();
                    var r = q.executeQuery("SELECT problem_id FROM java_solution_drafts LIMIT 1")) {
                assertTrue(r.next());
                var repo = new JavaSolutionDraftRepository();
                var draft = repo.findByProblemId(c, r.getLong(1)).orElseThrow();
                draft.setSourceCode("class Legacy {} // retained");
                repo.update(c, draft);
            }
            assertEquals(
                    "CONFLICT",
                    s.save(id(), presentation, d.revision(), "class Old {}", "").outcome());
            assertEquals("class Legacy {} // retained", s.read(presentation).source());
        }
    }

    @Test
    void receiptFailureRollsBackSourceNotesAndRevision() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            var presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            String command = id();
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute(
                        "CREATE TRIGGER inject_failure BEFORE INSERT ON desktop_draft_saves BEGIN"
                                + " SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            s.save(
                                    command,
                                    presentation,
                                    d.revision(),
                                    "class Uncommitted {}",
                                    "notes"));
            assertEquals(d, s.read(presentation));
            assertNull(s.status(command, presentation));
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DROP TRIGGER inject_failure");
            }
            assertEquals(
                    "SAVED",
                    s.save(command, presentation, d.revision(), "class Uncommitted {}", "notes")
                            .outcome());
        }
    }

    @Test
    void uncommittedUnknownAndMismatchedPresentationCannotReadOrSave() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            var presentation = open(p);
            var s = service(p);
            var missing = "desktop-practice-" + id();
            assertThrows(RuntimeException.class, () -> s.resume(missing));
            assertThrows(RuntimeException.class, () -> s.save(id(), missing, 0, "private", ""));
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DELETE FROM problem_solving_sessions");
            }
            assertThrows(RuntimeException.class, () -> s.read(presentation));
        }
    }

    @Test
    void oversizedAndInvalidUnicodeInputsLeaveDraftUnchanged() throws Exception {
        try (var p = ExperimentalProfile.openLearning(temporary.resolve("p"))) {
            var presentation = open(p);
            var s = service(p);
            var d = s.read(presentation);
            for (String source : List.of("x".repeat(32769), "\uD800"))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> s.save(id(), presentation, d.revision(), source, ""));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> s.save(id(), presentation, d.revision(), "ok", "n".repeat(8001)));
            assertEquals(d, s.read(presentation));
        }
    }

    @Test
    void corruptPrivateRowsAndMissingRevisionTriggerFailClosed() throws Exception {
        Path path = temporary.resolve("p");
        try (var p = ExperimentalProfile.openLearning(path)) {
            var presentation = open(p);
            var s = service(p);
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute(
                        "UPDATE java_solution_drafts SET"
                            + " source_code=char(0)||printf('%0100001d',0)");
            }
            assertThrows(RuntimeException.class, () -> s.read(presentation));
            try (var c = p.connection(false);
                    var q = c.createStatement()) {
                q.execute("DROP TRIGGER desktop_revision_java_solution_drafts_update");
            }
        }
        assertThrows(java.sql.SQLException.class, () -> ExperimentalProfile.openLearning(path));
    }

    @Test
    void draftProtocolRejectsExtraFieldsWrongVersionsAndBadRevision() throws Exception {
        String prefix =
                "{\"v\":1,\"id\":\""
                        + id()
                        + "\",\"method\":\"practice.draft.save\",\"profileId\":\""
                        + id()
                        + "\",\"sessionId\":\""
                        + id()
                        + "\",\"commandId\":\""
                        + id()
                        + "\",\"presentationId\":\"desktop-practice-"
                        + id()
                        + "\",\"expectedRevision\":0,\"source\":\"class X"
                        + " {}\",\"reflection\":\"\"}";
        assertEquals("practice.draft.save", PracticeProtocol.parse(prefix.getBytes()).method());
        for (String bad :
                List.of(
                        prefix.replace("\"v\":1", "\"v\":2"),
                        prefix.replace("\"expectedRevision\":0", "\"expectedRevision\":-1"),
                        prefix.replace("\"source\":", "\"path\":\"/private\",\"source\":")))
            assertThrows(Exception.class, () -> PracticeProtocol.parse(bad.getBytes()));
    }

    @Test
    void upgradeOfMarkedV1ProfilePreservesExistingPresentationAndDraft() throws Exception {
        Path path = temporary.resolve("v1");
        String presentation;
        try (var p = ExperimentalProfile.openLearning(path)) {
            presentation = open(p);
            var service = service(p);
            var draft = service.read(presentation);
            service.save(
                    id(),
                    presentation,
                    draft.revision(),
                    "class PreviouslySaved {}",
                    "existing notes");
            // Recreate the predecessor's exact absence of desktop draft metadata, keeping learner
            // tables.
            try (var c = p.connection(false);
                    var s = c.createStatement()) {
                for (String table : List.of("java_solution_drafts", "evolution_working_notes"))
                    for (String op : List.of("insert", "update", "delete"))
                        s.execute("DROP TRIGGER desktop_revision_" + table + "_" + op);
                s.execute("DROP TABLE desktop_draft_saves");
                s.execute("DROP TABLE desktop_draft_clock");
            }
        }
        try (var p = ExperimentalProfile.openLearning(path)) {
            new PracticeApplicationService(p, Clock.systemUTC());
            var resumed = service(p).resume(presentation);
            assertEquals("class PreviouslySaved {}", resumed.draft().source());
            assertEquals("existing notes", resumed.draft().reflection());
            assertEquals(0, resumed.draft().revision());
        }
    }
}
