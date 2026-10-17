package com.codefit.testsupport;

import java.sql.*;

/**
 * Removes later additive tables in isolated historical fixtures only. Not application downgrade.
 */
public final class AdaptationDowngrade {
    private AdaptationDowngrade() {}

    public static void remove(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            s.execute("DROP TABLE IF EXISTS evolution_reward_effort_scan");
            s.execute("DELETE FROM schema_migrations WHERE version=22");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_milestone_baseline_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_milestone_baseline");
            s.execute("DELETE FROM schema_migrations WHERE version=21");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_state_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_state_immutable_delete");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_entitlements_immutable_update");
            s.execute("DROP TRIGGER IF EXISTS evolution_reward_entitlements_immutable_delete");
            s.execute("DROP TABLE IF EXISTS evolution_reward_entitlements");
            s.execute("DROP TABLE IF EXISTS evolution_reward_state");
            s.execute("DROP TABLE IF EXISTS evolution_reward_preferences");
            s.execute("DELETE FROM schema_migrations WHERE version=20");
            s.execute("DROP TABLE IF EXISTS evolution_working_notes");
            s.execute("DELETE FROM schema_migrations WHERE version=19");
            s.execute("DROP TABLE IF EXISTS evolution_adaptation_choices");
            s.execute("DROP TABLE IF EXISTS evolution_adaptation_decisions");
            s.execute("DELETE FROM schema_migrations WHERE version=18");
        }
    }
}
