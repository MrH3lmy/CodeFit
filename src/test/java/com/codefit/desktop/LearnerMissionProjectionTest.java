package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.knowledge.KnowledgePreview;
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
class LearnerMissionProjectionTest {
    @TempDir Path dir;
    final String session = UUID.randomUUID().toString();
    final Instant captured = Instant.parse("2026-10-24T12:00:00Z");

    LearnerMissionProjection.Projection read(Path db) throws Exception {
        try (var c = LearnerSnapshot.openReadOnly(db, false)) {
            c.setAutoCommit(false);
            return LearnerMissionProjection.read(c, "a".repeat(64), session, captured);
        }
    }

    Path fixture(String mode) throws Exception {
        var file = dir.resolve(mode + ".db");
        LearnerFixture.create(file);
        MissionFixture.populate(file, mode);
        return file;
    }

    @Test
    void emptyHistoryDoesNotMeanAssignedWorkOrAuthorDraftApproval() throws Exception {
        Path file = fixture("empty");
        byte[] before = Files.readAllBytes(file);
        var p = read(file);
        assertEquals("AVAILABLE", p.historyStatus());
        assertTrue(p.activities().isEmpty());
        assertFalse(p.quests().isEmpty());
        assertEquals(3, p.dueScheduledCards());
        var drafts = KnowledgePreview.loadBundled().skills().stream().map(s -> s.id()).toList();
        assertTrue(p.quests().stream().noneMatch(q -> drafts.contains(q.id())));
        String wire =
                new String(
                        LearnerProtocol.result(session, p),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"currentEligibility\":\"NOT_EVALUATED\""));
        assertTrue(wire.contains("\"actionsEnabled\":false"));
        for (String draft : drafts) assertFalse(wire.contains(draft));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void actualDurableResultsAndRepeatedReadsDoNotWriteOrLeakPrivateContent() throws Exception {
        Path file = fixture("recorded");
        byte[] before = Files.readAllBytes(file);
        var p = read(file);
        assertEquals("AVAILABLE", p.historyStatus());
        assertEquals(3, p.activities().size());
        assertEquals("PREPARED", p.activities().get(0).stage());
        assertEquals("CANCELLED", p.activities().get(1).outcome());
        assertEquals("PASSED", p.activities().get(2).outcome());
        assertEquals(1, p.decisions().size());
        assertEquals(p, read(file));
        assertArrayEquals(before, Files.readAllBytes(file));
        String wire =
                new String(
                        LearnerProtocol.result(session, p),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(wire.contains("PRIVATE-"));
        assertFalse(wire.contains("class Counter"));
        assertFalse(wire.contains("diagnostics"));
        assertTrue(wire.length() < 65536);
    }

    @Test
    void corruptedDurableHistoryIsUnavailableNotAnInventedFailure() throws Exception {
        var p = read(fixture("corrupt"));
        assertEquals("UNAVAILABLE", p.historyStatus());
        assertTrue(p.activities().isEmpty());
    }

    @Test
    void missingOrConflictingCatalogNeverFallsBackToUnverifiedLabels() throws Exception {
        for (String mode : List.of("missing", "conflicting")) {
            var p = read(fixture(mode));
            assertTrue(p.quests().isEmpty());
            assertTrue(p.activities().stream().allMatch(a -> a.metadata().equals("UNAVAILABLE")));
        }
    }

    @Test
    void viewsAndFutureDecisionTimesAreRejectedWithoutLeakingTheirRows() throws Exception {
        Path file = fixture("empty");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var s = c.createStatement()) {
            s.execute("ALTER TABLE evolution_adaptation_decisions RENAME TO hidden_decisions");
            s.execute(
                    "CREATE VIEW evolution_adaptation_decisions AS SELECT * FROM hidden_decisions");
        }
        assertEquals("UNAVAILABLE", read(file).decisionStatus());
    }

    @Test
    void boundedRecentHistoryDoesNotScanOrReturnAllSubmissions() throws Exception {
        var p = read(fixture("large"));
        assertEquals("AVAILABLE", p.historyStatus());
        assertEquals(20, p.activities().size());
        assertTrue(p.historyTruncated());
        assertEquals("mission-submission-25", p.activities().getFirst().command());
    }

    @Test
    void withdrawnEvaluationNeverShowsItsOldPositiveOutcomeAsActive() throws Exception {
        var p = read(fixture("withdrawn"));
        var item =
                p.activities().stream()
                        .filter(a -> a.command().equals("mission-submission-1"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(item.withdrawn());
        assertNull(item.outcome());
        assertEquals(2, item.revision());
    }

    @Test
    void futureSubmissionAndDecisionTimesCannotBecomeSnapshotFacts() throws Exception {
        Path file = fixture("recorded");
        try (var c = LearnerSnapshot.openReadOnly(file, false)) {
            c.setAutoCommit(false);
            var p =
                    LearnerMissionProjection.read(
                            c, "a".repeat(64), session, MissionFixture.AT.minusSeconds(1));
            assertEquals("UNAVAILABLE", p.historyStatus());
            assertEquals("UNAVAILABLE", p.decisionStatus());
        }
    }

    @Test
    void exactVersionConflictDoesNotSubstituteALatestQuest() throws Exception {
        Path file = fixture("recorded");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var s = c.createStatement()) {
            s.execute("DROP TRIGGER evolution_quest_definitions_no_update");
            s.executeUpdate("UPDATE evolution_quest_definitions SET version=version+100");
        }
        var p = read(file);
        assertEquals("INCOMPLETE", p.catalogStatus());
        assertTrue(p.quests().isEmpty());
        assertTrue(p.activities().stream().allMatch(a -> a.metadata().equals("UNAVAILABLE")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "change",
                "reason",
                "actor",
                "digest",
                "payload",
                "oversized-result",
                "oversized-submission",
                "presentation",
                "presentation-time",
                "started-before",
                "finished-before-start",
                "future-finish"
            })
    void malformedPrivateHistoryFailsClosedWithoutWrites(String corruption) throws Exception {
        Path file = fixture("recorded");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = c.createStatement()) {
            // Deliberately corrupt only this isolated fixture, bypassing writer protections.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.execute("DROP TRIGGER evolution_evaluation_revisions_no_update");
            statement.execute("DROP TRIGGER evolution_submission_snapshot_immutable");
            statement.execute("DROP TRIGGER evolution_challenge_presentations_no_update");
            String column =
                    switch (corruption) {
                        case "change" -> "change_kind";
                        case "reason", "actor", "digest" -> corruption;
                        default -> null;
                    };
            if (column != null)
                statement.executeUpdate(
                        "UPDATE evolution_evaluation_revisions SET "
                                + column
                                + "='PRIVATE-INVALID'");
            else
                switch (corruption) {
                    case "payload" ->
                            statement.executeUpdate(
                                    "UPDATE evolution_evaluation_revisions SET payload=X'00'");
                    case "oversized-result" ->
                            statement.executeUpdate(
                                    "UPDATE evolution_evaluation_revisions SET"
                                        + " payload=zeroblob(2000001)");
                    case "oversized-submission" ->
                            statement.executeUpdate(
                                    "UPDATE evolution_submissions SET snapshot=zeroblob(2000001)");
                    case "presentation" ->
                            statement.executeUpdate(
                                    "UPDATE evolution_challenge_presentations SET"
                                        + " challenge_id='PRIVATE-OTHER'");
                    case "presentation-time" ->
                            statement.executeUpdate(
                                    "UPDATE evolution_challenge_presentations SET"
                                        + " presented_at='invalid-time'");
                    default -> {
                        byte[] bytes;
                        try (var rows =
                                statement.executeQuery(
                                        "SELECT payload FROM evolution_evaluation_revisions WHERE"
                                            + " submission_id=1")) {
                            assertTrue(rows.next());
                            bytes = rows.getBytes(1);
                        }
                        var old = com.codefit.evolution.evaluation.EvaluationCodec.result(bytes);
                        Instant start =
                                corruption.equals("started-before")
                                        ? MissionFixture.AT.minusSeconds(1)
                                        : corruption.equals("finished-before-start")
                                                ? MissionFixture.AT.plusSeconds(1)
                                                : old.startedAt();
                        Instant finish =
                                corruption.equals("future-finish")
                                        ? captured.plusSeconds(1)
                                        : old.finishedAt();
                        var changed =
                                new com.codefit.evolution.evaluation.EvaluationResult(
                                        old.outcome(),
                                        old.checks(),
                                        old.diagnostics(),
                                        start,
                                        finish,
                                        old.evaluatorVersion(),
                                        old.runtime());
                        byte[] payload =
                                com.codefit.evolution.evaluation.EvaluationCodec.encode(changed);
                        try (var update =
                                c.prepareStatement(
                                        "UPDATE evolution_evaluation_revisions SET"
                                            + " payload=?,digest=? WHERE submission_id=1")) {
                            update.setBytes(1, payload);
                            update.setString(
                                    2, com.codefit.evolution.quest.CatalogCodec.digest(payload));
                            update.executeUpdate();
                        }
                    }
                }
        }
        byte[] before = Files.readAllBytes(file);
        var result = read(file);
        assertEquals("UNAVAILABLE", result.historyStatus());
        assertTrue(result.activities().isEmpty());
        assertFalse(result.historyTruncated());
        assertArrayEquals(before, Files.readAllBytes(file));
        String wire =
                new String(
                        LearnerProtocol.result(session, result),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(wire.contains("PRIVATE-"));
        assertFalse(wire.contains("class Counter"));
        assertTrue(wire.contains("\"currentEligibility\":\"NOT_EVALUATED\""));
        assertTrue(wire.contains("\"actionsEnabled\":false"));
    }
}
