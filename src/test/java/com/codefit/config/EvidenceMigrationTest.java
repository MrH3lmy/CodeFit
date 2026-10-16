package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.repository.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvidenceMigrationTest {
    @TempDir Path directory;

    @BeforeEach
    void versionThirteen() throws Exception {
        DatabaseConfig.useDatabaseFile(directory.resolve("migration.db"));
        DatabaseConfig.initialize();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            com.codefit.testsupport.QuestCatalogDowngrade.remove(c);
            s.execute("PRAGMA foreign_keys=OFF");
            var tables = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE"
                                + " 'evolution_evidence_%'")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for (String t : tables) s.execute("DROP TABLE " + t);
            s.execute("DELETE FROM schema_migrations WHERE version=14");
            s.execute("UPDATE user_progress SET xp=789,level=12,streak_days=7 WHERE id=1");
            s.execute(
                    "INSERT INTO"
                        + " review_history(flashcard_id,rating,previous_interval_days,new_interval_days,validation_result,submitted_answer,hint_usage_recorded)"
                        + " SELECT MIN(id),'GOOD',7,12,NULL,'PRIVATE_LEGACY_ANSWER',0 FROM"
                        + " flashcards");
            s.execute(
                    "INSERT INTO"
                        + " assessment_attempts(assessment_item_id,variant_index,skill_category,module_name,correct,submitted_answer)"
                        + " SELECT MIN(id),0,'Concurrency','Concurrency',1,'PRIVATE_ASSESSMENT'"
                        + " FROM assessment_items");
        }
    }

    @Test
    void populatedVersionThirteenAndRepeatedInitializationPreserveAllSourceTables()
            throws Exception {
        var before = rows();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            SchemaMigrator.migrate(c);
        }
        assertEquals(before, rows());
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();
        assertEquals(before, rows());
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            assertEquals(19, scalar(s, "SELECT MAX(version) FROM schema_migrations"));
            assertEquals(1, scalar(s, "SELECT COUNT(*) FROM schema_migrations WHERE version=14"));
            assertEquals(3, scalar(s, "SELECT COUNT(*) FROM evolution_evidence_mapping_rules"));
            assertEquals(0, scalar(s, "SELECT COUNT(*) FROM evolution_evidence_observations"));
            assertEquals(0, scalar(s, "SELECT COUNT(*) FROM evolution_evidence_cursors"));
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
            assertEquals(
                    JavaDebuggingSkillSeed.GRAPH,
                    new EvidenceMappingRepository()
                            .find(c, EvidenceMappingRepository.INITIAL_MAPPING)
                            .orElseThrow()
                            .graph());
        }
    }

    @Test
    void partialDdlFailureRollsBackVersionAndTablesAndCanRetry() throws Exception {
        var before = rows();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE evolution_evidence_mapping_rules(unexpected TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(c));
            assertEquals(13, scalar(s, "SELECT MAX(version) FROM schema_migrations"));
            assertEquals(
                    0,
                    scalar(
                            s,
                            "SELECT COUNT(*) FROM sqlite_master WHERE"
                                + " name='evolution_evidence_mappings'"));
            s.execute("DROP TABLE evolution_evidence_mapping_rules");
            SchemaMigrator.migrate(c);
        }
        assertEquals(before, rows());
    }

    private static long scalar(Statement s, String sql) throws SQLException {
        try (var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static Map<String, List<List<String>>> rows() throws SQLException {
        var result = new TreeMap<String, List<List<String>>>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE"
                                + " 'evolution_evidence_%' AND name NOT LIKE 'evolution_challenge_%'"
                                + " AND name NOT LIKE 'evolution_quest_%'"
                                + " AND name <> 'evolution_assessment_reservations' AND name NOT"
                                + " IN('schema_migrations','sqlite_sequence','evolution_submissions','evolution_evaluation_runs','evolution_evaluation_revisions','evolution_evaluation_assessment_aliases','evolution_estimate_epochs','evolution_estimate_revisions','evolution_baseline_choices','assessment_attempt_facts','evolution_adaptation_decisions','evolution_adaptation_choices','evolution_working_notes') ORDER BY name")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for (String t : tables) {
                var records = new ArrayList<List<String>>();
                try (var r = s.executeQuery("SELECT * FROM \"" + t + "\" ORDER BY rowid")) {
                    while (r.next()) {
                        var record = new ArrayList<String>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                            record.add(r.getString(i));
                        records.add(record);
                    }
                }
                result.put(t, records);
            }
        }
        return result;
    }
}
