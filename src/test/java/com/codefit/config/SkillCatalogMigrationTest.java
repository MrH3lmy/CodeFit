package com.codefit.config;

import com.codefit.repository.SkillCatalogRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SkillCatalogMigrationTest {
    private static final List<String> CATALOG_TABLES = List.of("evolution_skill_prerequisites", "evolution_graph_skills",
            "evolution_skill_graphs", "evolution_skill_behaviors", "evolution_skill_definitions");

    @Test
    void populatedVersionTwelveUpgradesAndRepeatedStartupPreservesAllExistingRows() throws SQLException {
        prepareVersionTwelve();
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO decks (id, name, description) VALUES (900001, 'Private legacy deck', 'Do not rewrite')");
            sql.execute("""
                    INSERT INTO flashcards (id, deck_id, front, back, accepted_answers, skill_category, due_date, interval_days, ease_factor, review_count)
                    VALUES (900001, 900001, 'Private prompt', 'Private answer', 'Private answer', 'Existing string category', '2026-11-02', 27, 2.8, 8)
                    """);
            sql.execute("""
                    INSERT INTO review_history (flashcard_id, rating, previous_interval_days, new_interval_days,
                        validation_result, submitted_answer, hint_used, hint_usage_recorded, reviewed_at)
                    VALUES (900001, 'GOOD', 14, 27, NULL, 'legacy self rating', 0, 0, '2026-10-10 12:00:00')
                    """);
            sql.execute("UPDATE user_progress SET xp = 1123, level = 12, streak_days = 9, total_reviews = 80 WHERE id = 1");
            sql.execute("""
                    INSERT INTO problems (id, platform, external_code, title, topic)
                    VALUES (900001, 'LEGACY_FIXTURE', 'history-1', 'Original workbook problem', 'Existing topic')
                    """);
            sql.execute("INSERT INTO roadmap_entries (problem_id, stage, sequence_order, mandatory) VALUES (900001, 'D3', 900001, 1)");
            sql.execute("""
                    INSERT INTO problem_progress (problem_id, state, solved_with, completed_at, mistake_notes)
                    VALUES (900001, 'SOLVED', 'HINT', '2026-10-01T10:20:30', 'Private reflection')
                    """);
            sql.execute("""
                    INSERT INTO problem_attempts (problem_id, attempt_number, submission_result, notes, completion_origin, submitted_at)
                    VALUES (900001, 1, 'AC', 'Original learner-reported judge result', 'UNKNOWN', '2026-10-01 10:20:30')
                    """);
            sql.execute("INSERT INTO problem_solving_sessions (problem_id, phase, thinking_seconds_elapsed, active, paused) VALUES (900001, 'THINKING', 42, 1, 1)");
            sql.execute("""
                    INSERT INTO java_solution_drafts (problem_id, main_class_name, source_code, stdin, expected_output)
                    VALUES (900001, 'Main', 'public class Main {}', 'private input', 'private output')
                    """);
            sql.execute("""
                    INSERT INTO assessment_attempts (assessment_item_id, variant_index, skill_category, module_name, correct, submitted_answer, run_id)
                    SELECT MIN(id), 0, 'Existing assessment category', 'Existing module', 1, 'Self-reported assessment', 'legacy-run'
                    FROM assessment_items
                    """);
        }
        Map<String, List<List<String>>> before = legacyRows();
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();
        assertLegacyRows(before);
        assertEquals(JavaDebuggingSkillSeed.graph(), new SkillCatalogRepository().findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow());
        try (Connection connection = DatabaseConfig.getConnection()) {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 13"));
            assertEquals(4, scalar(connection, "SELECT COUNT(*) FROM evolution_skill_definitions"));
            assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM evolution_skill_prerequisites"));
        }
    }

    @Test
    void catalogMigrationAlonePreservesEvenLegacyFieldsThatStartupNormallyBackfills() throws SQLException {
        prepareVersionTwelve();
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO flashcards (id, deck_id, front, back, due_date, accepted_answers)
                    SELECT 900002, MIN(id), 'Legacy null answer', 'Existing reference', '2026-12-01', NULL FROM decks
                    """);
        }
        var before = legacyRows();
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA foreign_keys = ON");
            SchemaMigrator.migrate(connection);
        }
        assertLegacyRows(before);
        // DatabaseConfig.ensureFlashcardColumns already backfills NULL accepted_answers on every
        // startup. That pre-existing normalization is separate from the additive catalog migration.
        // Restore a normal initialized fixture so the other migration cases are order-independent.
        DatabaseConfig.initialize();
    }

    @Test
    void seedReplayAndRestartDoNotDuplicateOrRelabelDefinitions() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA foreign_keys = ON");
            connection.setAutoCommit(false);
            JavaDebuggingSkillSeed.seed(connection);
            JavaDebuggingSkillSeed.seed(connection);
            connection.commit();
        }
        DatabaseConfig.initialize();
        assertEquals(JavaDebuggingSkillSeed.graph(), new SkillCatalogRepository().findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow());
        try (Connection connection = DatabaseConfig.getConnection()) {
            assertEquals(4, scalar(connection, "SELECT COUNT(*) FROM evolution_skill_definitions WHERE domain_id = 'software-engineering'"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM evolution_skill_graphs WHERE graph_id = 'java-debugging-concurrency'"));
            assertEquals(4, scalar(connection, "SELECT COUNT(*) FROM evolution_skill_behaviors WHERE skill_id LIKE 'java.%'"));
        }
    }

    @Test
    void failedMigrationRollsBackSchemaAndVersionAndCanBeRetried() throws SQLException {
        prepareVersionTwelve();
        Map<String, List<List<String>>> before = legacyRows();
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            // Forces failure after the first new tables have been created, without touching old data.
            sql.execute("CREATE TABLE evolution_skill_graphs (unexpected TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(connection));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 13"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'evolution_skill_definitions'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'evolution_skill_behaviors'"));
            assertLegacyRows(before);
            sql.execute("DROP TABLE evolution_skill_graphs");
        }
        DatabaseConfig.initialize();
        assertEquals(JavaDebuggingSkillSeed.graph(), new SkillCatalogRepository().findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow());
    }

    /** Reconstructs the pre-Evolution version-12 schema/data in a throwaway database. */
    private static void prepareVersionTwelve() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            com.codefit.testsupport.QuestCatalogDowngrade.remove(connection);
            sql.execute("PRAGMA foreign_keys = OFF");
            // Later additive migrations depend on the catalog. Remove those only in this
            // downgrade fixture, then retain the original v12 -> head preservation assertions.
            var evidenceTables = new ArrayList<String>();
            try (var rows = sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'evolution_evidence_%'")) {
                while (rows.next()) evidenceTables.add(rows.getString(1));
            }
            for (String table : evidenceTables) sql.execute("DROP TABLE " + table);
            sql.execute("DELETE FROM schema_migrations WHERE version > 13");
            for (String table : CATALOG_TABLES) sql.execute("DROP TABLE " + table);
            sql.execute("DELETE FROM schema_migrations WHERE version = 13");
            assertEquals(12, scalar(connection, "SELECT MAX(version) FROM schema_migrations"));
        }
    }

    private static Map<String, List<List<String>>> legacyRows() throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = sql.executeQuery("""
                    SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'evolution_%'
                        AND name NOT IN ('schema_migrations', 'sqlite_sequence') ORDER BY name
                    """)) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            for (String table : tables) {
                List<List<String>> contents = new ArrayList<>();
                try (ResultSet rows = sql.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY rowid")) {
                    while (rows.next()) {
                        List<String> values = new ArrayList<>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) values.add(rows.getString(i));
                        contents.add(values);
                    }
                }
                snapshot.put(table, contents);
            }
        }
        return snapshot;
    }

    private static void assertLegacyRows(Map<String, List<List<String>>> expected) throws SQLException {
        var actual = legacyRows();
        assertEquals(expected.keySet(), actual.keySet());
        for (String table : expected.keySet()) {
            assertEquals(expected.get(table).size(), actual.get(table).size(), "Row count in " + table);
            for (int i = 0; i < expected.get(table).size(); i++) {
                assertEquals(expected.get(table).get(i), actual.get(table).get(i), "Row " + i + " in " + table);
            }
        }
    }

    private static int scalar(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            rows.next(); return rows.getInt(1);
        }
    }
}
