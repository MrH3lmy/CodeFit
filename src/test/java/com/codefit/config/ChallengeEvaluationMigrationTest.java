package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.testsupport.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ChallengeEvaluationMigrationTest {
    @BeforeEach
    void versionFifteen() throws Exception {
        try (var c = DatabaseConfig.getConnection()) {
            EvaluationDowngrade.remove(c);
        }
    }

    @Test
    void migrationSixteenAndRepeatedStartupPreserveAllExistingCatalogEvidenceAndLearnerRows()
            throws Exception {
        var before = snapshot();
        try (var c = DatabaseConfig.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        DatabaseConfig.initialize();
        DatabaseConfig.initialize();
        assertEquals(before, snapshot());
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            try (var r =
                    s.executeQuery(
                            "SELECT MAX(version),COUNT(*) FROM schema_migrations WHERE"
                                + " version=16")) {
                assertTrue(r.next());
                assertEquals(16, r.getInt(1));
                assertEquals(1, r.getInt(2));
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
        }
    }

    @Test
    void failureHalfwayThroughDdlRollsBackTablesAndVersionAndCanRetry() throws Exception {
        var before = snapshot();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE evolution_evaluation_revisions(unexpected TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(c));
            try (var r = s.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                r.next();
                assertEquals(15, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE name"
                                + " IN('evolution_submissions','evolution_evaluation_runs')")) {
                r.next();
                assertEquals(0, r.getInt(1));
            }
            s.execute("DROP TABLE evolution_evaluation_revisions");
            SchemaMigrator.migrate(c);
        }
        assertEquals(before, snapshot());
    }

    private Map<String, List<List<String>>> snapshot() throws Exception {
        var out = new TreeMap<String, List<List<String>>>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT"
                                + " IN('schema_migrations','sqlite_sequence','evolution_submissions','evolution_evaluation_runs','evolution_evaluation_revisions','evolution_evaluation_assessment_aliases','evolution_estimate_epochs','evolution_estimate_revisions','evolution_baseline_choices','assessment_attempt_facts','evolution_adaptation_decisions','evolution_adaptation_choices','evolution_working_notes','evolution_reward_entitlements','evolution_reward_state','evolution_reward_preferences','evolution_reward_milestone_baseline','evolution_reward_effort_scan','evolution_reward_effort_processed')"
                                + " ORDER BY name")) {
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
                out.put(table, rows);
            }
        }
        return out;
    }
}
