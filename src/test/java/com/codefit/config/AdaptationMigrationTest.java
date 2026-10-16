package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.adaptation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.testsupport.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class AdaptationMigrationTest {
    @BeforeEach
    void historical() throws Exception {
        new MasteryEstimateService().pilot(Instant.parse("2026-06-12T12:00:00Z"));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            AdaptationDowngrade.remove(c);
            s.execute("UPDATE user_progress SET xp=432,level=5,streak_days=7 WHERE id=1");
        }
    }

    @Test
    void populatedSeventeenUpgradeAndRepeatedStartupPreserveAllPriorRows() throws Exception {
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
                                + " version=18")) {
                assertTrue(r.next());
                assertEquals(18, r.getInt(1));
                assertEquals(1, r.getInt(2));
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
            for (var table :
                    List.of("evolution_adaptation_choices", "evolution_adaptation_decisions")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    r.next();
                    assertEquals(0, r.getLong(1));
                }
            }
        }
    }

    @Test
    void partialDdlRollsBackAndRetrySucceeds() throws Exception {
        var before = snapshot();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE evolution_adaptation_choices(conflict TEXT)");
            assertThrows(SQLException.class, () -> SchemaMigrator.migrate(c));
            try (var r = s.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                r.next();
                assertEquals(17, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE"
                                + " name='evolution_adaptation_decisions'")) {
                r.next();
                assertEquals(0, r.getInt(1));
            }
            s.execute("DROP TABLE evolution_adaptation_choices");
            SchemaMigrator.migrate(c);
        }
        assertEquals(before, snapshot());
    }

    @Test
    void sqlGuardsProtectHistoricalDecisionsAndResponses() throws Exception {
        try (var c = DatabaseConfig.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        var service = new AdaptiveQuestService();
        var d = service.recommend(Instant.now());
        service.respond(LearnerCommand.simple("pause", AdaptationTypes.CommandKind.PAUSE));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            for (var table :
                    List.of("evolution_adaptation_choices", "evolution_adaptation_decisions")) {
                assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM " + table));
                assertThrows(
                        SQLException.class,
                        () -> s.executeUpdate("UPDATE " + table + " SET sequence=sequence"));
            }
        }
        assertEquals(d, new AdaptationDecisionRepository().find(d.id()).orElseThrow());
    }

    private Map<String, List<List<String>>> snapshot() throws Exception {
        var result = new TreeMap<String, List<List<String>>>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN"
                                + " ('schema_migrations','sqlite_sequence','evolution_adaptation_decisions','evolution_adaptation_choices')"
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
                result.put(table, rows);
            }
        }
        return result;
    }
}
