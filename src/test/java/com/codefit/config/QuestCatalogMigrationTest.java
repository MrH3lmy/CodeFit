package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.testsupport.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class QuestCatalogMigrationTest {
    @BeforeEach
    void versionFourteen() throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            QuestCatalogDowngrade.remove(c);
            s.execute("DELETE FROM problems WHERE platform='CODEFIT_EVOLUTION'");
            s.execute(
                    "INSERT OR IGNORE INTO problems(id,platform,external_code,title,topic)"
                        + " VALUES(900010,'LEGACY','preserve','Learner history','Private')");
            s.execute(
                    "INSERT OR IGNORE INTO"
                        + " java_solution_drafts(problem_id,main_class_name,source_code,stdin,expected_output)"
                        + " VALUES(900010,'Private','private learner source','input','output')");
            s.execute(
                    "INSERT OR IGNORE INTO"
                        + " problem_solving_sessions(problem_id,phase,thinking_seconds_elapsed,notes,active,paused)"
                        + " VALUES(900010,'THINKING',47,'private session',1,1)");
            s.execute(
                    "INSERT OR IGNORE INTO"
                        + " problem_progress(problem_id,state,solved_with,mistake_notes)"
                        + " VALUES(900010,'SOLVED','HINT','private history')");
            s.execute("UPDATE user_progress SET xp=527,level=7,streak_days=4 WHERE id=1");
        }
    }

    @Test
    void additiveMigrationAndRepeatedStartupPreserveEveryExistingNoncatalogRow() throws Exception {
        var before = snapshot();
        migrate();
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();
        assertEquals(before, snapshot());
        var catalog = new QuestCatalogRepository();
        assertEquals(7, catalog.quests().size());
        catalog.transaction(
                c -> {
                    try (var s = c.createStatement();
                            var rs = s.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(rs.next());
                    }
                    try (var s = c.createStatement();
                            var rs =
                                    s.executeQuery(
                                            "SELECT COUNT(*) FROM problems WHERE"
                                                + " platform='CODEFIT_EVOLUTION'")) {
                        rs.next();
                        assertEquals(12, rs.getInt(1));
                    }
                    return null;
                });
    }

    @Test
    void lateMigrationFailureRollsBackDdlBindingsAndVersionThenRetries() throws Exception {
        var before = snapshot();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE evolution_quest_definitions(unexpected TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(c));
            try (var rs = s.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                rs.next();
                assertEquals(14, rs.getInt(1));
            }
            try (var rs =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM problems WHERE platform='CODEFIT_EVOLUTION'")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (var rs =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE"
                                + " name='evolution_challenge_definitions'")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            s.execute("DROP TABLE evolution_quest_definitions");
        }
        assertEquals(before, snapshot());
        migrate();
        assertEquals(before, snapshot());
    }

    private void migrate() throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            SchemaMigrator.migrate(c);
        }
    }

    private Map<String, List<List<String>>> snapshot() throws SQLException {
        Map<String, List<List<String>>> result = new TreeMap<>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (var rs =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE"
                                + " 'evolution_quest_%' AND name NOT LIKE 'evolution_challenge_%'"
                                + " AND name NOT"
                                + " IN('evolution_assessment_reservations','schema_migrations','sqlite_sequence','evolution_submissions','evolution_evaluation_runs','evolution_evaluation_revisions','evolution_evaluation_assessment_aliases','evolution_estimate_epochs','evolution_estimate_revisions','evolution_baseline_choices','assessment_attempt_facts','evolution_adaptation_decisions','evolution_adaptation_choices','evolution_working_notes')"
                                + " ORDER BY name")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            for (String table : tables) {
                List<List<String>> rows = new ArrayList<>();
                try (var rs =
                        s.executeQuery(
                                "SELECT * FROM "
                                        + table
                                        + (table.equals("problems")
                                                ? " WHERE platform <> 'CODEFIT_EVOLUTION'"
                                                : table.equals("assessment_items")
                                                        ? " WHERE skill_category NOT LIKE"
                                                              + " 'Evolution %'"
                                                        : table.equals("assessment_variants")
                                                                ? " WHERE assessment_item_id IN"
                                                                      + " (SELECT id FROM"
                                                                      + " assessment_items WHERE"
                                                                      + " skill_category NOT LIKE"
                                                                      + " 'Evolution %')"
                                                                : "")
                                        + " ORDER BY rowid")) {
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                            row.add(rs.getString(i));
                        rows.add(row);
                    }
                }
                result.put(table, rows);
            }
        }
        return result;
    }
}
