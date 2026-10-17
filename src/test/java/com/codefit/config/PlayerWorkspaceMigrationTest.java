package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.evaluation.EvaluationTypes.*;
import com.codefit.evolution.player.PlayerSystemService;
import com.codefit.evolution.quest.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PlayerWorkspaceMigrationTest {
    @Test
    void populatedEighteenUpgradeAndRepeatedStartupPreserveEveryOriginalRow() throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_milestone_baseline");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_scan");
            s.execute("DELETE FROM schema_migrations WHERE version=22");
            s.execute("DELETE FROM schema_migrations WHERE version=21");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_state_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_state_immutable_delete");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_entitlements_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_entitlements_immutable_delete");
            s.execute("DROP TABLE evolution_reward_entitlements");
            s.execute("DROP TABLE evolution_reward_state");
            s.execute("DROP TABLE evolution_reward_preferences");
            s.execute("DELETE FROM schema_migrations WHERE version=20");
            s.execute("DROP TABLE evolution_working_notes");
            s.execute("DELETE FROM schema_migrations WHERE version=19");
            s.execute("UPDATE user_progress SET xp=432,level=5,streak_days=7 WHERE id=1");
            var before = snapshot(c);
            SchemaMigrator.migrate(c);
            DatabaseConfig.initialize();
            DatabaseConfig.initialize();
            assertEquals(before, snapshot(c));
            try (var r =
                    s.executeQuery(
                            "SELECT MAX(version),COUNT(*) FROM schema_migrations WHERE"
                                    + " version=19")) {
                r.next();
                assertEquals(19, r.getInt(1));
                assertEquals(1, r.getInt(2));
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
        }
    }

    @Test
    void draftAndNotesSaveRollBackTogetherOnFailure() throws Exception {
        var player = new PlayerSystemService();
        var open =
                player.openBaseline(
                        new CatalogRef("baseline-counter", 1),
                        QuestTypes.Familiarity.UNKNOWN,
                        false);
        player.save(
                new PlayerSystemService.Draft(
                        open.presentationId(),
                        "old source",
                        "old reflection",
                        OutsideAssistance.UNKNOWN));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TRIGGER fail_notes BEFORE UPDATE ON evolution_working_notes BEGIN"
                            + " SELECT RAISE(ABORT,'fixture write failure'); END");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            player.save(
                                    new PlayerSystemService.Draft(
                                            open.presentationId(),
                                            "new source",
                                            "new reflection",
                                            OutsideAssistance.DECLARED)));
            s.execute("DROP TRIGGER fail_notes");
        }
        var resumed = player.resume(open.presentationId());
        assertEquals("old source", resumed.source());
        assertEquals("old reflection", resumed.reflection());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.save(
                                new PlayerSystemService.Draft(
                                        "unknown-presentation",
                                        "no",
                                        "",
                                        OutsideAssistance.UNKNOWN)));
    }

    private Map<String, List<List<String>>> snapshot(Connection c) throws Exception {
        var result = new TreeMap<String, List<List<String>>>();
        var tables = new ArrayList<String>();
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT"
                                    + " IN('schema_migrations','sqlite_sequence','evolution_working_notes','evolution_reward_entitlements','evolution_reward_state','evolution_reward_preferences','evolution_reward_milestone_baseline','evolution_reward_effort_scan')"
                                    + " ORDER BY name")) {
            while (r.next()) tables.add(r.getString(1));
        }
        for (String t : tables)
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT * FROM \"" + t + "\" ORDER BY rowid")) {
                var rows = new ArrayList<List<String>>();
                while (r.next()) {
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        row.add(
                                r.getBytes(i) == null
                                        ? null
                                        : Base64.getEncoder().encodeToString(r.getBytes(i)));
                    rows.add(row);
                }
                result.put(t, rows);
            }
        return result;
    }
}
