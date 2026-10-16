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
class MasteryMigrationTest {
    @BeforeEach
    void historical() throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            MasteryDowngrade.remove(c);
            s.execute(
                    "INSERT INTO"
                        + " assessment_attempts(assessment_item_id,variant_index,skill_category,module_name,correct,submitted_answer)"
                        + " SELECT"
                        + " MIN(id),0,'Concurrency','Concurrency',1,'PRESERVED_PRIVATE_ANSWER' FROM"
                        + " assessment_items");
            s.execute("UPDATE user_progress SET xp=222,level=3,streak_days=4 WHERE id=1");
        }
    }

    @Test
    void populatedSixteenUpgradeAndRepeatedStartupPreserveEveryPriorRow() throws Exception {
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
                                    + " version=17")) {
                r.next();
                assertEquals(17, r.getInt(1));
                assertEquals(1, r.getInt(2));
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
            for (String t :
                    List.of(
                            "assessment_attempt_facts",
                            "evolution_estimate_epochs",
                            "evolution_estimate_revisions",
                            "evolution_baseline_choices")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    r.next();
                    assertEquals(0, r.getInt(1));
                }
            }
        }
    }

    @Test
    void partialDdlRollsBackAndCanRetry() throws Exception {
        var before = snapshot();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE evolution_baseline_choices(conflict TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(c));
            try (var r = s.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                r.next();
                assertEquals(16, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE name IN"
                                + " ('evolution_estimate_epochs','evolution_estimate_revisions')")) {
                r.next();
                assertEquals(0, r.getInt(1));
            }
            s.execute("DROP TABLE evolution_baseline_choices");
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
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN"
                                + " ('schema_migrations','sqlite_sequence','evolution_estimate_epochs','evolution_estimate_revisions','evolution_baseline_choices','assessment_attempt_facts')"
                                + " ORDER BY name")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for (String t : tables) {
                var rows = new ArrayList<List<String>>();
                try (var r = s.executeQuery("SELECT * FROM \"" + t + "\" ORDER BY rowid")) {
                    while (r.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                            row.add(r.getString(i));
                        rows.add(row);
                    }
                }
                out.put(t, rows);
            }
        }
        return out;
    }
}
