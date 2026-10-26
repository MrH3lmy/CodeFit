package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.quest.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class PracticeApplicationIntegrationTest {
    @TempDir Path temporary;
    private String previous;

    @BeforeEach
    void save() {
        previous = DatabaseConfig.currentDatabaseUrl();
    }

    @AfterEach
    void restore() {
        DatabaseConfig.useDatabaseUrl(previous);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static CatalogRef quest(PracticeApplicationService.Candidate x) {
        return new CatalogRef(x.questId(), x.questVersion());
    }

    private static CatalogRef challenge(PracticeApplicationService.Candidate x) {
        return new CatalogRef(x.challengeId(), x.challengeVersion());
    }

    @Test
    void openingCommitsBeforeContentAndReplaysAcrossRestartWithoutRewardOrEstimateWrites()
            throws Exception {
        Path directory = temporary.resolve("practice");
        String command = id(), profileId, oldSession;
        PracticeApplicationService.Candidate selected;
        PracticeApplicationService.Opened opened;
        try (var profile = ExperimentalProfile.openLearning(directory)) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            profileId = app.profileId();
            oldSession = app.session();
            byte[] before = Files.readAllBytes(profile.database());
            selected = app.list().getFirst();
            assertArrayEquals(before, Files.readAllBytes(profile.database()));
            assertNull(app.status(command));
            opened = app.open(command, quest(selected), challenge(selected));
            assertFalse(opened.instructions().isBlank());
            assertEquals(opened.receipt(), app.status(command));
            assertEquals(opened, app.open(command, quest(selected), challenge(selected)));
            try (var c = profile.connection(true);
                    var s = c.createStatement()) {
                for (var table :
                        List.of(
                                "evolution_estimate_revisions",
                                "evolution_adaptation_decisions",
                                "evolution_evidence_observations",
                                "problem_attempts")) {
                    try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(r.next());
                        assertEquals(0, r.getInt(1), table);
                    }
                }
                try (var r =
                        s.executeQuery("SELECT COUNT(*) FROM evolution_challenge_presentations")) {
                    assertTrue(r.next());
                    assertEquals(1, r.getInt(1));
                }
            }
        }
        try (var profile = ExperimentalProfile.openLearning(directory)) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            assertThrows(
                    IllegalArgumentException.class, () -> app.authorize(profileId, oldSession));
            assertEquals(opened.receipt(), app.status(command));
            assertEquals(opened, app.open(command, quest(selected), challenge(selected)));
            var otherQuest =
                    JavaTrainingPack.quests().stream()
                            .filter(
                                    q ->
                                            q.cohort() == QuestTypes.Cohort.PRACTICE
                                                    && !q.ref().equals(quest(selected)))
                            .findFirst()
                            .orElseThrow();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> app.open(command, otherQuest.ref(), otherQuest.challenges().getFirst()));
        }
    }

    @Test
    void reservedUnknownOutdatedAndDraftIdentitiesNeverCreateExposure() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(temporary.resolve("practice"))) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            var x = app.list().getFirst();
            for (var q : JavaTrainingPack.quests())
                if (q.cohort() != QuestTypes.Cohort.PRACTICE)
                    for (var c : q.challenges())
                        assertThrows(
                                IllegalArgumentException.class, () -> app.open(id(), q.ref(), c));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> app.open(id(), quest(x), new CatalogRef(x.challengeId(), 999)));
            int rejected = 0;
            for (var skill : com.codefit.knowledge.KnowledgePreview.loadBundled().skills())
                for (var stage : skill.stages()) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> app.open(id(), quest(x), new CatalogRef(stage.id(), 1)));
                    rejected++;
                }
            assertEquals(16, rejected);
            try (var c = profile.connection(true);
                    var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT COUNT(*) FROM evolution_challenge_presentations")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test
    void rollbackLeavesNoReceiptAndNoProtectedContentCanReturn() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(temporary.resolve("practice"))) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            var selected = app.list().getFirst();
            String command = id();
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute(
                        "CREATE TRIGGER reject_presentation BEFORE INSERT ON"
                                + " evolution_challenge_presentations BEGIN SELECT"
                                + " RAISE(ABORT,'isolated test interruption'); END");
            }
            assertThrows(
                    Exception.class, () -> app.open(command, quest(selected), challenge(selected)));
            assertNull(app.status(command));
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER reject_presentation");
            }
            assertNotNull(app.open(command, quest(selected), challenge(selected)).receipt());
        }
    }

    @Test
    void currentPauseIsRecheckedAndPreparedInputCannotBeRebound() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(temporary.resolve("practice"))) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            var selected = app.list().getFirst();
            new com.codefit.evolution.adaptation.AdaptiveQuestService()
                    .respond(
                            com.codefit.evolution.adaptation.LearnerCommand.simple(
                                    id(),
                                    com.codefit.evolution.adaptation.AdaptationTypes.CommandKind
                                            .PAUSE));
            assertTrue(app.list().isEmpty());
            String command = id();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> app.open(command, quest(selected), challenge(selected)));
            assertNull(app.status(command));
            var other =
                    JavaTrainingPack.quests().stream()
                            .filter(
                                    q ->
                                            q.cohort() == QuestTypes.Cohort.PRACTICE
                                                    && !q.ref().equals(quest(selected)))
                            .findFirst()
                            .orElseThrow();
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> app.open(command, other.ref(), other.challenges().getFirst()));
            assertEquals("COMMAND_ID_CONFLICT", error.getMessage());
        }
    }

    @Test
    void corruptCatalogCannotAuthorizeOrListContent() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(temporary.resolve("practice"))) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            var selected = app.list().getFirst();
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                var triggers = new ArrayList<String>();
                try (var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='trigger' AND"
                                    + " tbl_name='evolution_quest_definitions'")) {
                    while (r.next()) triggers.add(r.getString(1));
                }
                for (var trigger : triggers) s.execute("DROP TRIGGER \"" + trigger + "\"");
                s.execute("UPDATE evolution_quest_definitions SET digest='corrupt-test-fixture'");
            }
            assertThrows(Exception.class, app::list);
            assertThrows(
                    Exception.class, () -> app.open(id(), quest(selected), challenge(selected)));
        }
    }

    @Test
    void incompatibleMarkedProfileIsNotMigrated() throws Exception {
        Path directory = temporary.resolve("practice");
        Path database;
        try (var profile = ExperimentalProfile.openLearning(directory)) {
            database = profile.database();
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute("DELETE FROM schema_migrations WHERE version=25");
            }
        }
        byte[] before = Files.readAllBytes(database);
        assertThrows(Exception.class, () -> ExperimentalProfile.openLearning(directory));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void javaProtocolRejectsUnknownMethodsExtraFieldsAndStaleSessions() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(temporary.resolve("practice"))) {
            var app = new PracticeApplicationService(profile, Clock.systemUTC());
            String request =
                    "{\"v\":1,\"id\":\""
                            + id()
                            + "\",\"method\":\"practice.list\",\"profileId\":\""
                            + app.profileId()
                            + "\",\"sessionId\":\""
                            + app.session()
                            + "\"}";
            assertEquals(
                    "practice.list",
                    PracticeProtocol.parse(
                                    request.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .method());
            for (String malformed :
                    List.of(
                            request.replace("practice.list", "evaluate"),
                            request.replace("}", ",\"sql\":\"select 1\"}"),
                            request.replace("\"v\":1", "\"v\":1,\"v\":1")))
                assertThrows(
                        Exception.class,
                        () ->
                                PracticeProtocol.parse(
                                        malformed.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8)));
            assertThrows(
                    IllegalArgumentException.class, () -> app.authorize(app.profileId(), id()));
            assertThrows(IllegalArgumentException.class, () -> app.authorize(id(), app.session()));
        }
    }

    @Test
    void ownershipRejectsSecondWriterAndUnmarkedProfiles() throws Exception {
        Path directory = temporary.resolve("practice");
        try (var profile = ExperimentalProfile.openLearning(directory)) {
            assertThrows(Exception.class, () -> ExperimentalProfile.openLearning(directory));
            assertThrows(Exception.class, () -> ProfileLock.acquire(profile.database()));
            assertThrows(Exception.class, () -> ExperimentalProfile.open(directory));
        }
        Path real = temporary.resolve("real");
        Files.createDirectories(real);
        Files.writeString(real.resolve("codefit.db"), "do not modify");
        assertThrows(Exception.class, () -> ExperimentalProfile.openLearning(real));
        assertEquals("do not modify", Files.readString(real.resolve("codefit.db")));
    }
}
