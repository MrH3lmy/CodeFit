package com.codefit.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Additive local reward history; the existing XP/streak balance remains the opening state. */
final class ProgressionSchema {
    private ProgressionSchema() {}

    static void create(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_reward_preferences (
                      id INTEGER PRIMARY KEY CHECK(id=1),
                      enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN(0,1))
                    )
                    """);
            s.execute("INSERT INTO evolution_reward_preferences(id,enabled) VALUES(1,1)");
            s.execute(
                    """
                    CREATE TABLE evolution_reward_state (
                      id INTEGER PRIMARY KEY CHECK(id=1),
                      historical_evidence_through INTEGER NOT NULL CHECK(historical_evidence_through>=0),
                      historical_submission_through INTEGER NOT NULL CHECK(historical_submission_through>=0)
                    )
                    """);
            s.execute(
                    """
                    INSERT INTO evolution_reward_state
                      (id,historical_evidence_through,historical_submission_through)
                    SELECT 1,
                      (SELECT COALESCE(MAX(sequence),0) FROM evolution_evidence_observations),
                      (SELECT COALESCE(MAX(id),0) FROM evolution_submissions)
                    """);
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_state_immutable_update
                    BEFORE UPDATE ON evolution_reward_state
                    BEGIN SELECT RAISE(ABORT,'Progression source boundary is immutable'); END
                    """);
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_state_immutable_delete
                    BEFORE DELETE ON evolution_reward_state
                    BEGIN SELECT RAISE(ABORT,'Progression source boundary is immutable'); END
                    """);
            s.execute(
                    """
                    CREATE TABLE evolution_reward_entitlements (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                      entitlement_key TEXT NOT NULL UNIQUE,
                      reward_type TEXT NOT NULL CHECK(reward_type IN
                        ('EFFORT','INDEPENDENT_MILESTONE','RETENTION_MILESTONE')),
                      subject_id TEXT NOT NULL,
                      definition_version INTEGER NOT NULL CHECK(definition_version>0),
                      policy_version TEXT NOT NULL,
                      xp_awarded INTEGER NOT NULL CHECK(xp_awarded>=0),
                      status TEXT NOT NULL CHECK(status IN('AWARDED','OPTED_OUT')),
                      source_ref TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      earned_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                    )
                    """);
            s.execute(
                    "CREATE INDEX evolution_reward_history ON"
                            + " evolution_reward_entitlements(sequence DESC)");
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_entitlements_immutable_update
                    BEFORE UPDATE ON evolution_reward_entitlements
                    BEGIN SELECT RAISE(ABORT,'Reward history is immutable'); END
                    """);
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_entitlements_immutable_delete
                    BEFORE DELETE ON evolution_reward_entitlements
                    BEGIN SELECT RAISE(ABORT,'Reward history is immutable'); END
                    """);
        }
    }
}
