package com.codefit.repository;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A test-only durable evaluator; production #214 is deliberately absent. */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ChallengeEvidenceBoundaryTest {
    @TempDir Path directory;
    private static final SkillRef SKILL = new SkillRef("java.concurrency.atomic-update", 1);
    private static final EvidenceMappingRef MAP = new EvidenceMappingRef("test-challenge", 1);
    private final EvidenceRepository repo =
            new EvidenceRepository(
                    (c, o) -> {
                        try (var s =
                                c.prepareStatement(
                                        "SELECT payload FROM test_durable_evaluations WHERE"
                                                + " id=?")) {
                            s.setLong(1, o.source().recordId());
                            try (var r = s.executeQuery()) {
                                return r.next() && EvidenceCodec.decode(r.getBytes(1)).sameFacts(o);
                            }
                        }
                    });

    @BeforeEach
    void fresh() throws Exception {
        DatabaseConfig.useDatabaseFile(directory.resolve("test.db"));
        DatabaseConfig.initialize();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE test_durable_evaluations(id INTEGER PRIMARY KEY,payload BLOB NOT"
                            + " NULL)");
        }
        new EvidenceMappingRepository()
                .publish(
                        new EvidenceMapping(
                                MAP,
                                JavaDebuggingSkillSeed.GRAPH,
                                List.of(
                                        new EvidenceMapping.Rule(
                                                SourceType.CHALLENGE_EVALUATION,
                                                "family-one",
                                                SKILL),
                                        new EvidenceMapping.Rule(
                                                SourceType.PROBLEM_ATTEMPT,
                                                "Explicit fixture",
                                                SKILL))));
    }

    private EvidenceObservation evaluation(long id, String submission, Long attempt) {
        return new EvidenceObservation(
                new EvidenceSourceRef(SourceType.CHALLENGE_EVALUATION, id, 1),
                new EvidenceTime(
                        "2026-10-10T12:00:00Z",
                        Instant.parse("2026-10-10T12:00:00Z"),
                        TimeBasis.EXACT_UTC),
                Provenance.LOCAL_CHECK,
                Outcome.PASS,
                Assistance.NONE_OBSERVED,
                null,
                Exposure.RELATED_VARIANT,
                Set.of(Limitation.OUTSIDE_ASSISTANCE_UNKNOWN),
                Availability.AVAILABLE,
                "CHECKS_PASSED",
                "CHALLENGE",
                "family:family-one",
                new EvaluationReference(
                        submission,
                        "family-one",
                        "rubric-v1",
                        "evaluator-v1",
                        "0".repeat(64),
                        List.of("termination", "invariant", "output"),
                        attempt),
                Change.ORIGINAL,
                null,
                ChangeReason.ORIGINAL_RECORD,
                Actor.SYSTEM);
    }

    private void persist(Connection c, EvidenceObservation o) throws SQLException {
        try (var s =
                c.prepareStatement("INSERT OR REPLACE INTO test_durable_evaluations VALUES(?,?)")) {
            s.setLong(1, o.source().recordId());
            s.setBytes(2, EvidenceCodec.encode(o));
            s.executeUpdate();
        }
    }

    private List<EvidenceView> views() {
        return repo.forSkill(SKILL, MAP, 0, 20);
    }

    @Test
    void noLocalCheckWithoutMatchingDurableEvaluationOrFromOldRunner() {
        var o = evaluation(1, "submission-1", null);
        assertThrows(IllegalArgumentException.class, () -> repo.append(o, MAP, "family-one"));
        repo.transaction(
                c -> {
                    persist(c, o);
                    return null;
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceRepository().append(o, MAP, "family-one"));
        assertThrows(IllegalArgumentException.class, () -> repo.append(o, MAP, "unrelated-family"));
        assertEquals(AppendResult.INSERTED, repo.append(o, MAP, "family-one"));
        assertTrue(views().getFirst().objectivelyEvaluated());
        assertEquals(3, views().getFirst().evidence().observation().evaluation().checkIds().size());
        assertEquals(1, views().size());
        assertFalse(new EvidenceRepository().forSkill(SKILL, MAP, 0, 10).getFirst().effective());
    }

    @Test
    void sourceAliasesAndMultipleChecksCannotCreateAnotherIndependentSuccess() throws Exception {
        var o = evaluation(1, "submission-1", null);
        var alias = evaluation(2, "submission-1", null);
        repo.transaction(
                c -> {
                    persist(c, o);
                    persist(c, alias);
                    return null;
                });
        repo.append(o, MAP, "family-one");
        assertThrows(IllegalStateException.class, () -> repo.append(alias, MAP, "family-one"));
        assertEquals(1, views().size());
        assertEquals(AppendResult.DUPLICATE, repo.append(o, MAP, "family-one"));
        var changedManifest =
                new EvidenceObservation(
                        o.source(),
                        o.time(),
                        o.provenance(),
                        o.outcome(),
                        o.assistance(),
                        null,
                        o.exposure(),
                        o.limitations(),
                        o.availability(),
                        o.resultCode(),
                        o.originCode(),
                        o.correlationKey(),
                        new EvaluationReference(
                                "submission-1",
                                "family-one",
                                "rubric-v1",
                                "evaluator-v1",
                                "0".repeat(64),
                                List.of("another-check"),
                                null),
                        o.change(),
                        null,
                        o.reason(),
                        o.actor());
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.append(changedManifest, MAP, "family-one"));
    }

    @Test
    void linkedManualAttemptStaysExcludedEvenAfterEvaluationRetraction() throws Exception {
        long attempt;
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "INSERT INTO problems(platform,external_code,title,topic)"
                            + " VALUES('EV','p','Fixture','Explicit fixture')");
            s.execute(
                    "INSERT INTO"
                        + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin)"
                        + " SELECT MAX(id),1,'AC','FRESH_ATTEMPT' FROM problems");
            try (var r = s.executeQuery("SELECT MAX(id) FROM problem_attempts")) {
                r.next();
                attempt = r.getLong(1);
            }
        }
        var manual =
                repo.transaction(
                        c ->
                                new LearningEvidenceSourceRepository()
                                        .find(
                                                c,
                                                new EvidenceSourceRef(
                                                        SourceType.PROBLEM_ATTEMPT, attempt, 1))
                                        .orElseThrow()
                                        .observation());
        repo.append(manual, MAP, "Explicit fixture");
        var o = evaluation(1, "submission-1", attempt);
        repo.transaction(
                c -> {
                    persist(c, o);
                    repo.append(c, o, MAP, "family-one");
                    return null;
                });
        assertTrue(views().getFirst().exclusions().contains(Exclusion.EVALUATION_AUTHORITATIVE));
        assertEquals(1, views().stream().filter(EvidenceView::effective).count());
        var alias = evaluation(2, "submission-2", attempt);
        repo.transaction(
                c -> {
                    persist(c, alias);
                    return null;
                });
        assertThrows(IllegalStateException.class, () -> repo.append(alias, MAP, "family-one"));
        repo.append(o.retract(ChangeReason.BROKEN_CHECK, Actor.SYSTEM), MAP, "family-one");
        assertEquals(0, views().stream().filter(EvidenceView::effective).count());
    }

    @Test
    void correctionUpdatesEvaluationWithoutCreatingANewSubmissionAndAtomicRollbackWorks() {
        var original = evaluation(1, "submission-1", null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        repo.transaction(
                                c -> {
                                    persist(c, original);
                                    repo.append(c, original, MAP, "family-one");
                                    throw new IllegalStateException("failure after append");
                                }));
        assertTrue(views().isEmpty());
        assertThrows(
                IllegalArgumentException.class, () -> repo.append(original, MAP, "family-one"));
        repo.transaction(
                c -> {
                    persist(c, original);
                    repo.append(c, original, MAP, "family-one");
                    return null;
                });
        var revision =
                original.revise(
                        2,
                        EvidenceIds.id(original.source()),
                        ChangeReason.BROKEN_CHECK,
                        Actor.SYSTEM);
        repo.transaction(
                c -> {
                    persist(c, revision);
                    repo.append(c, revision, MAP, "family-one");
                    return null;
                });
        assertEquals(2, views().size());
        assertEquals(1, views().stream().filter(EvidenceView::effective).count());
        repo.transaction(
                c -> {
                    try (var s = c.createStatement()) {
                        s.execute("DELETE FROM test_durable_evaluations");
                    }
                    return null;
                });
        assertEquals(0, views().stream().filter(EvidenceView::effective).count());
        assertTrue(views().getLast().exclusions().contains(Exclusion.SOURCE_UNAVAILABLE));
    }
}
