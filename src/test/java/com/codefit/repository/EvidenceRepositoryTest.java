package com.codefit.repository;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;
import com.codefit.service.EvidenceIngestionService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvidenceRepositoryTest {
    @TempDir Path directory;
    private final EvidenceRepository repo = new EvidenceRepository();
    private final LearningEvidenceSourceRepository sources = new LearningEvidenceSourceRepository();
    private static final SkillRef SKILL = new SkillRef("java.concurrency.atomic-update", 1);
    private static final EvidenceMappingRef MAP = new EvidenceMappingRef("test-evidence", 1);

    @BeforeEach
    void fresh() {
        DatabaseConfig.useDatabaseFile(directory.resolve("isolated.db"));
        DatabaseConfig.initialize();
        mapping(
                MAP,
                List.of(
                        new EvidenceMapping.Rule(
                                SourceType.PROBLEM_ATTEMPT, "Explicit fixture", SKILL),
                        new EvidenceMapping.Rule(SourceType.REVIEW, "Explicit fixture", SKILL),
                        new EvidenceMapping.Rule(
                                SourceType.ASSESSMENT_ATTEMPT, "Explicit fixture", SKILL)));
    }

    private void mapping(EvidenceMappingRef ref, List<EvidenceMapping.Rule> rules) {
        new EvidenceMappingRepository()
                .publish(new EvidenceMapping(ref, JavaDebuggingSkillSeed.GRAPH, rules));
    }

    private static void sql(String sql) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private long problem(int attempts) throws SQLException {
        sql(
                "INSERT INTO problems(platform,external_code,title,topic)"
                        + " VALUES('EVIDENCE','fixture','Private title','Explicit fixture')");
        long id = scalar("SELECT MAX(id) FROM problems");
        for (int i = 1; i <= attempts; i++)
            sql(
                    "INSERT INTO"
                        + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin,notes,submitted_at)"
                        + " VALUES("
                            + id
                            + ","
                            + i
                            + ",'AC','FRESH_ATTEMPT','DO_NOT_COPY_NOTES','2026-10-10 12:00:00')");
        return scalar("SELECT MAX(id) FROM problem_attempts");
    }

    private long review(String validation) throws SQLException {
        sql(
                "INSERT INTO flashcards(deck_id,front,back,skill_category,due_date) SELECT"
                    + " MIN(id),'PRIVATE_FRONT','PRIVATE_BACK','Explicit fixture','2026-10-20' FROM"
                    + " decks");
        long card = scalar("SELECT MAX(id) FROM flashcards");
        sql(
                "INSERT INTO"
                    + " review_history(flashcard_id,rating,previous_interval_days,new_interval_days,validation_result,hint_used,hint_usage_recorded,submitted_answer)"
                    + " VALUES("
                        + card
                        + ",'GOOD',3,9,'"
                        + validation
                        + "',1,1,'DO_NOT_COPY_ANSWER')");
        return scalar("SELECT MAX(id) FROM review_history");
    }

    private EvidenceObservation source(SourceType type, long id) {
        return repo.transaction(
                c ->
                        sources.find(c, new EvidenceSourceRef(type, id, 1))
                                .orElseThrow()
                                .observation());
    }

    private List<EvidenceView> views() {
        return repo.forSkill(SKILL, MAP, 0, 256);
    }

    @Test
    void immutableReplayAndConflictingPayloadsCannotInflateEvidence() throws Exception {
        long id = problem(1);
        var o = source(SourceType.PROBLEM_ATTEMPT, id);
        assertEquals(AppendResult.INSERTED, repo.append(o, MAP, "Explicit fixture"));
        var first = views().getFirst();
        assertEquals(AppendResult.DUPLICATE, repo.append(o, MAP, "Explicit fixture"));
        assertEquals(List.of(first), views());
        assertTrue(first.effective());
        assertFalse(first.objectivelyEvaluated());
        sql("UPDATE problem_attempts SET submission_result='WA' WHERE id=" + id);
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.append(source(SourceType.PROBLEM_ATTEMPT, id), MAP, "Explicit fixture"));
        assertTrue(views().getFirst().exclusions().contains(Exclusion.SOURCE_CHANGED));
        // Replay still cannot replace facts or restore source availability.
        repo.append(o, MAP, "Explicit fixture");
        assertEquals(1, views().size());
    }

    @Test
    void initialIngestionRejectsForgedFactsAndSelectors() throws Exception {
        long id = problem(1);
        var forged =
                LegacyEvidenceClassifier.problem(
                        id, 1, 1, "AC", "FRESH_ATTEMPT", "2026-10-10 12:00:00");
        assertThrows(
                IllegalArgumentException.class, () -> repo.append(forged, MAP, "Explicit fixture"));
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.append(source(SourceType.PROBLEM_ATTEMPT, id), MAP, "Fuzzy category"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
    }

    @Test
    void sourceCorrectionAndRetractionNeverResurrectOldSuccess() throws Exception {
        long id = problem(1);
        var original = source(SourceType.PROBLEM_ATTEMPT, id);
        repo.append(original, MAP, "Explicit fixture");
        sql("UPDATE problem_attempts SET submission_result='WA' WHERE id=" + id);
        var service = new EvidenceIngestionService();
        service.refresh(
                SourceType.PROBLEM_ATTEMPT, id, MAP, ChangeReason.SOURCE_CORRECTED, Actor.LEARNER);
        assertEquals(
                AppendResult.DUPLICATE,
                service.refresh(
                        SourceType.PROBLEM_ATTEMPT,
                        id,
                        MAP,
                        ChangeReason.SOURCE_CORRECTED,
                        Actor.LEARNER));
        var list = views();
        assertEquals(2, list.size());
        assertTrue(list.getFirst().exclusions().contains(Exclusion.SUPERSEDED));
        assertTrue(list.getLast().effective());
        assertEquals(Outcome.FAIL, list.getLast().evidence().observation().outcome());
        var revised = list.getLast().evidence().observation();
        var retracted = revised.retract(ChangeReason.LEARNER_RETRACTED, Actor.LEARNER);
        repo.append(retracted, MAP, "Explicit fixture");
        repo.append(original, MAP, "Explicit fixture");
        assertEquals(0, views().stream().filter(EvidenceView::effective).count());
        assertTrue(views().getLast().exclusions().contains(Exclusion.RETRACTED));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repo.append(
                                original.revise(
                                        2,
                                        EvidenceIds.id(original.source()),
                                        ChangeReason.SOURCE_CORRECTED,
                                        Actor.SYSTEM),
                                MAP,
                                "Explicit fixture"));
        // Multiple revisions are explicit, never extra solves; arbitrary jumps and branches fail.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repo.append(
                                original.revise(
                                        5,
                                        EvidenceIds.id(retracted.source()),
                                        ChangeReason.SOURCE_CORRECTED,
                                        Actor.SYSTEM),
                                MAP,
                                "Explicit fixture"));
    }

    @Test
    void missingSourceAndParentAreExcludedWithoutFailure() throws Exception {
        long id = review("EXACT");
        repo.append(source(SourceType.REVIEW, id), MAP, "Explicit fixture");
        assertTrue(views().getFirst().objectivelyEvaluated());
        sql(
                "DELETE FROM flashcards WHERE id=(SELECT flashcard_id FROM review_history WHERE id="
                        + id
                        + ")");
        var view = views().getFirst();
        assertFalse(view.effective());
        assertEquals(Availability.SOURCE_UNAVAILABLE, view.currentAvailability());
        assertEquals(Outcome.PASS, view.evidence().observation().outcome());
        repo.append(
                view.evidence()
                        .observation()
                        .retract(ChangeReason.LEARNER_RETRACTED, Actor.LEARNER),
                MAP,
                "Explicit fixture");
        assertTrue(views().getLast().exclusions().contains(Exclusion.RETRACTED));
    }

    @Test
    void deletedSourceRowRemainsUnavailableAndRetractionsCannotRewriteFacts() throws Exception {
        long id = problem(1);
        var original = source(SourceType.PROBLEM_ATTEMPT, id);
        repo.append(original, MAP, "Explicit fixture");
        var retraction = original.retract(ChangeReason.LEARNER_RETRACTED, Actor.LEARNER);
        var forged =
                new EvidenceObservation(
                        retraction.source(),
                        retraction.time(),
                        Provenance.LEGACY_UNSPECIFIED,
                        retraction.outcome(),
                        retraction.assistance(),
                        null,
                        retraction.exposure(),
                        retraction.limitations(),
                        retraction.availability(),
                        retraction.resultCode(),
                        retraction.originCode(),
                        retraction.correlationKey(),
                        null,
                        retraction.change(),
                        retraction.previousEvidenceId(),
                        retraction.reason(),
                        retraction.actor());
        assertThrows(
                IllegalArgumentException.class, () -> repo.append(forged, MAP, "Explicit fixture"));
        sql("DELETE FROM problem_attempts WHERE id=" + id);
        assertEquals(Availability.SOURCE_UNAVAILABLE, views().getFirst().currentAvailability());
        assertFalse(views().getFirst().effective());
        repo.append(retraction, MAP, "Explicit fixture");
        assertTrue(views().getLast().exclusions().contains(Exclusion.RETRACTED));
    }

    @Test
    void mappingUpgradesReinterpretWithoutCreatingSourceEvents() throws Exception {
        long id = review("EXACT");
        var o = source(SourceType.REVIEW, id);
        repo.append(o, MAP, "Explicit fixture");
        var newer = new EvidenceMappingRef(MAP.mappingId(), 2);
        mapping(
                newer,
                List.of(new EvidenceMapping.Rule(SourceType.REVIEW, "Explicit fixture", SKILL)));
        repo.append(o, newer, "Explicit fixture");
        assertEquals(1, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
        assertTrue(views().getFirst().exclusions().contains(Exclusion.MAPPING_SUPERSEDED));
        var newViews = repo.forSkill(SKILL, newer, 0, 10);
        assertEquals(1, newViews.size());
        assertTrue(newViews.getFirst().effective());
        assertEquals(views().getFirst().evidence().id(), newViews.getFirst().evidence().id());
        assertThrows(
                IllegalArgumentException.class, () -> repo.append(o, newer, "Renamed context"));
        var removed = new EvidenceMappingRef(MAP.mappingId(), 3);
        mapping(removed, List.of());
        assertTrue(repo.forSkill(SKILL, removed, 0, 10).isEmpty());
        assertFalse(repo.forSkill(SKILL, newer, 0, 10).getFirst().effective());
    }

    @Test
    void mappingMustBeExactPublishedGraphMemberAndVersion() throws Exception {
        assertThrows(
                RuntimeException.class,
                () ->
                        mapping(
                                new EvidenceMappingRef("invalid-skill", 1),
                                List.of(
                                        new EvidenceMapping.Rule(
                                                SourceType.REVIEW,
                                                "Explicit fixture",
                                                new SkillRef(SKILL.skillId(), 2)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapping(new EvidenceMappingRef(MAP.mappingId(), 3), List.of()));
        assertThrows(IllegalArgumentException.class, () -> mapping(MAP, List.of()));
        assertTrue(repo.forSkill(new SkillRef(SKILL.skillId(), 2), MAP, 0, 10).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.forSkill(SKILL, new EvidenceMappingRef("missing", 1), 0, 10));
    }

    @Test
    void categoryChangesRequireExplicitRevisionAndNeverInferSimilarNames() throws Exception {
        long id = review("EXACT");
        var original = source(SourceType.REVIEW, id);
        repo.append(original, MAP, "Explicit fixture");
        sql(
                "UPDATE flashcards SET skill_category='explicit fixture' WHERE id=(SELECT"
                        + " flashcard_id FROM review_history WHERE id="
                        + id
                        + ")");
        assertTrue(views().getFirst().exclusions().contains(Exclusion.SOURCE_CHANGED));
        new EvidenceIngestionService()
                .refresh(SourceType.REVIEW, id, MAP, ChangeReason.SOURCE_CORRECTED, Actor.SYSTEM);
        assertEquals(1, views().size());
        assertFalse(views().getFirst().effective());
        assertEquals(2, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
    }

    @Test
    void repeatedAttemptsRetainSharedProblemCorrelationAndNoUnseenClaim() throws Exception {
        problem(2);
        var service = new EvidenceIngestionService();
        assertEquals(2, service.ingestBatch(MAP, SourceType.PROBLEM_ATTEMPT, 10).processed());
        var list = views();
        assertEquals(2, list.size());
        assertEquals(
                list.getFirst().evidence().observation().correlationKey(),
                list.getLast().evidence().observation().correlationKey());
        assertEquals(Exposure.REPEAT, list.getLast().evidence().observation().exposure());
    }

    @Test
    void restartSafeFiniteBackfillScansPastDashboardLimitsAndLeavesSourcesUnchanged()
            throws Exception {
        problem(301);
        sql(
                "INSERT INTO"
                    + " assessment_attempts(assessment_item_id,variant_index,skill_category,module_name,correct,submitted_answer)"
                    + " SELECT MIN(id),0,'Unmapped category','m',1,'PRIVATE_ASSESSMENT' FROM"
                    + " assessment_items");
        var before = sourceRows();
        int processed = 0;
        for (int i = 0; i < 5; i++) {
            var result =
                    new EvidenceIngestionService().ingestBatch(MAP, SourceType.PROBLEM_ATTEMPT, 64);
            assertTrue(result.processed() <= 64);
            processed += result.processed();
        }
        assertEquals(301, processed);
        assertEquals(
                0,
                new EvidenceIngestionService()
                        .ingestBatch(MAP, SourceType.PROBLEM_ATTEMPT, 64)
                        .processed());
        assertEquals(
                1,
                new EvidenceIngestionService()
                        .ingestBatch(MAP, SourceType.ASSESSMENT_ATTEMPT, 10)
                        .processed());
        assertEquals(302, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
        assertEquals(before, sourceRows());
        int count = 0;
        long cursor = 0;
        while (true) {
            var page = repo.forSkill(SKILL, MAP, cursor, 47);
            if (page.isEmpty()) break;
            count += page.size();
            cursor = page.getLast().evidence().sequence();
        }
        assertEquals(301, count);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceIngestionService().ingestBatch(MAP, SourceType.REVIEW, 257));
    }

    @Test
    void injectedMidBatchFailureRollsBackCursorInterpretationsAndObservations() throws Exception {
        long last = problem(3);
        sql(
                "CREATE TRIGGER evidence_test_failure BEFORE INSERT ON"
                        + " evolution_evidence_observations WHEN NEW.revision=1 AND (SELECT"
                        + " source_record_id FROM evolution_evidence_sources WHERE"
                        + " root_id=NEW.root_id)="
                        + last
                        + " BEGIN SELECT RAISE(ABORT,'injected'); END");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new EvidenceIngestionService()
                                .ingestBatch(MAP, SourceType.PROBLEM_ATTEMPT, 10));
        assertEquals(0, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM evolution_evidence_sources"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM evolution_evidence_cursors"));
        sql("DROP TRIGGER evidence_test_failure");
        assertEquals(
                3,
                new EvidenceIngestionService()
                        .ingestBatch(MAP, SourceType.PROBLEM_ATTEMPT, 10)
                        .processed());
    }

    @Test
    void remapBackfillUsesCurrentRevisionIncludingRetractions() throws Exception {
        long id = review("EXACT");
        var o = source(SourceType.REVIEW, id);
        repo.append(o, MAP, "Explicit fixture");
        repo.append(
                o.retract(ChangeReason.LEARNER_RETRACTED, Actor.LEARNER), MAP, "Explicit fixture");
        var newer = new EvidenceMappingRef(MAP.mappingId(), 2);
        mapping(
                newer,
                List.of(new EvidenceMapping.Rule(SourceType.REVIEW, "Explicit fixture", SKILL)));
        sql(
                "UPDATE flashcards SET skill_category='Renamed after retraction' WHERE id=(SELECT"
                        + " flashcard_id FROM review_history WHERE id="
                        + id
                        + ")");
        new EvidenceIngestionService().ingestBatch(newer, SourceType.REVIEW, 10);
        var list = repo.forSkill(SKILL, newer, 0, 10);
        assertEquals(1, list.size());
        assertTrue(list.getFirst().exclusions().contains(Exclusion.RETRACTED));
        assertEquals(2, scalar("SELECT COUNT(*) FROM evolution_evidence_observations"));
    }

    @Test
    void callerOwnedReadSnapshotSupportsPaginationAndNeverCommitsWrites() throws Exception {
        long id = problem(1);
        var observation = source(SourceType.PROBLEM_ATTEMPT, id);
        try (var c = DatabaseConfig.getConnection();
                var statement = c.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            assertThrows(IllegalArgumentException.class, () -> repo.forSkill(c, SKILL, MAP, 0, 10));
            c.setAutoCommit(false);
            repo.append(c, observation, MAP, "Explicit fixture");
            var page = repo.forSkill(c, SKILL, MAP, 0, 1);
            assertEquals(1, page.size());
            assertTrue(page.getFirst().effective());
            assertTrue(
                    repo.forSkill(c, SKILL, MAP, page.getFirst().evidence().sequence(), 1)
                            .isEmpty());
            c.rollback();
        }
        assertTrue(views().isEmpty());
    }

    @Test
    void concurrencySerializesReplayBeforeRead() throws Exception {
        long id = problem(1);
        var o = source(SourceType.PROBLEM_ATTEMPT, id);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return repo.append(o, MAP, "Explicit fixture");
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return repo.append(o, MAP, "Explicit fixture");
                            });
            start.countDown();
            assertEquals(
                    Set.of(AppendResult.INSERTED, AppendResult.DUPLICATE),
                    Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(1, views().size());
    }

    @Test
    void immutableSqlHistoryCannotBeRewrittenOrDeleted() throws Exception {
        long id = review("EXACT");
        repo.append(source(SourceType.REVIEW, id), MAP, "Explicit fixture");
        for (String table :
                List.of(
                        "evolution_evidence_observations",
                        "evolution_evidence_interpretations",
                        "evolution_evidence_mapping_rules",
                        "evolution_evidence_mappings"))
            assertThrows(SQLException.class, () -> sql("DELETE FROM " + table));
        assertThrows(
                SQLException.class,
                () -> sql("UPDATE evolution_evidence_observations SET revision=99"));
    }

    private static Map<String, List<List<String>>> sourceRows() throws SQLException {
        var result = new TreeMap<String, List<List<String>>>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE"
                                    + " 'evolution_%' AND name NOT"
                                    + " IN('sqlite_sequence','schema_migrations') ORDER BY name")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for (String table : tables) {
                var rows = new ArrayList<List<String>>();
                try (var r = s.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY rowid")) {
                    while (r.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                            row.add(r.getString(i));
                        rows.add(row);
                    }
                }
                result.put(table, rows);
            }
        }
        return result;
    }
}
