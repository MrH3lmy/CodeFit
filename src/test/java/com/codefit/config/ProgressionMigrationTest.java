package com.codefit.config;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProgressionMigrationTest {
    @Test
    void migrationPreservesOpeningProgressAndIsIdempotent() throws Exception {
        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_milestone_baseline");
            s.execute("DROP TRIGGER evolution_reward_state_immutable_update");
            s.execute("DROP TRIGGER evolution_reward_state_immutable_delete");
            s.execute("DROP TRIGGER evolution_reward_entitlements_immutable_update");
            s.execute("DROP TRIGGER evolution_reward_entitlements_immutable_delete");
            s.execute("DROP TABLE evolution_reward_entitlements");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_scan");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_processed");
            s.execute("DROP TABLE evolution_reward_state");
            s.execute("DROP TABLE evolution_reward_preferences");
            s.execute("DELETE FROM schema_migrations WHERE version IN(20,21,22,23)");
            s.execute("UPDATE user_progress SET xp=678,level=7,streak_days=12 WHERE id=1");

            SchemaMigrator.migrate(c);
            SchemaMigrator.migrate(c);

            try (var r =
                    s.executeQuery(
                            "SELECT xp,level,streak_days FROM user_progress WHERE id=1")) {
                assertTrue(r.next());
                assertEquals(678, r.getInt(1));
                assertEquals(7, r.getInt(2));
                assertEquals(12, r.getInt(3));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM schema_migrations WHERE version=20")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM schema_migrations WHERE version=21")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
            }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM evolution_reward_entitlements")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT historical_evidence_through FROM evolution_reward_state"
                                    + " WHERE id=1")) {
                assertTrue(r.next());
                assertEquals(0, r.getLong(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT historical_submission_through FROM evolution_reward_state"
                                    + " WHERE id=1")) {
                assertTrue(r.next());
                assertEquals(0, r.getLong(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                                    + " AND name='evolution_reward_effort_scan'")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM evolution_reward_effort_processed")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }
}
