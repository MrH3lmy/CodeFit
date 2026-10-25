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
}
