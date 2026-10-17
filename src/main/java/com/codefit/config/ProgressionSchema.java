package com.codefit.config;

import com.codefit.evolution.mastery.*;

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

    /** Freeze only mastery milestones already observable before the prospective reward boundary. */
    static void preserveHistoricalMilestones(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_reward_milestone_baseline (
                      reward_type TEXT NOT NULL CHECK(reward_type IN
                        ('INDEPENDENT_MILESTONE','RETENTION_MILESTONE')),
                      subject_id TEXT NOT NULL,
                      definition_version INTEGER NOT NULL CHECK(definition_version>0),
                      source_revision INTEGER NOT NULL,
                      PRIMARY KEY(reward_type,subject_id,definition_version)
                    )
                    """);
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_milestone_baseline_immutable_update
                    BEFORE UPDATE ON evolution_reward_milestone_baseline
                    BEGIN SELECT RAISE(ABORT,'Historical milestone baseline is immutable'); END
                    """);
            s.execute(
                    """
                    CREATE TRIGGER evolution_reward_milestone_baseline_immutable_delete
                    BEFORE DELETE ON evolution_reward_milestone_baseline
                    BEGIN SELECT RAISE(ABORT,'Historical milestone baseline is immutable'); END
                    """);
        }
        var latest = new java.util.LinkedHashMap<String, HistoricalEstimate>();
        try (var s =
                        c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT revision,graph_id,graph_version,skill_id,skill_version,"
                                        + "input_sha,inputs FROM evolution_estimate_revisions"
                                        + " ORDER BY revision")) {
            while (r.next()) {
                byte[] bytes = r.getBytes("inputs");
                if (!com.codefit.evolution.quest.CatalogCodec.digest(bytes)
                        .equals(r.getString("input_sha")))
                    throw new SQLException("Historical estimate digest mismatch");
                String key =
                        r.getString("graph_id")
                                + ":v"
                                + r.getInt("graph_version")
                                + ":"
                                + r.getString("skill_id")
                                + ":v"
                                + r.getInt("skill_version");
                latest.put(
                        key,
                        new HistoricalEstimate(
                                r.getLong("revision"),
                                r.getInt("skill_version"),
                                EstimateCodec.decode(bytes)));
            }
        } catch (RuntimeException invalid) {
            throw new SQLException("Historical milestone baseline is invalid", invalid);
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_reward_milestone_baseline"
                                + "(reward_type,subject_id,definition_version,source_revision)"
                                + " VALUES(?,?,?,?)")) {
            for (var row : latest.values()) {
                MasteryEstimate estimate;
                try {
                    estimate = new OrdinalMasteryPolicy().estimate(row.input());
                } catch (RuntimeException invalid) {
                    throw new SQLException("Historical milestone estimate is invalid", invalid);
                }
                if (estimate.contradicted()
                        || !estimate.currentCapabilitySupported()
                        || estimate.freshness() != MasteryTypes.Freshness.CURRENT)
                    continue;
                if (estimate.band().ordinal() >= MasteryTypes.Band.INDEPENDENT.ordinal()) {
                    s.setString(1, "INDEPENDENT_MILESTONE");
                    s.setString(2, estimate.skill().skillId());
                    s.setInt(3, row.skillVersion());
                    s.setLong(4, row.revision());
                    s.addBatch();
                }
                if (estimate.band() == MasteryTypes.Band.RETENTION_SUPPORTED) {
                    s.setString(1, "RETENTION_MILESTONE");
                    s.setString(2, estimate.skill().skillId());
                    s.setInt(3, row.skillVersion());
                    s.setLong(4, row.revision());
                    s.addBatch();
                }
            }
            s.executeBatch();
        }
    }

    static void createEffortScanState(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_reward_effort_scan (
                      id INTEGER PRIMARY KEY CHECK(id=1),
                      last_submission_id INTEGER NOT NULL CHECK(last_submission_id>=0)
                    )
                    """);
            s.execute(
                    """
                    INSERT INTO evolution_reward_effort_scan(id,last_submission_id)
                    SELECT 1,historical_submission_through FROM evolution_reward_state WHERE id=1
                    """);
        }
    }

    static void createEffortProcessed(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE evolution_reward_effort_scan");
            s.execute(
                    """
                    CREATE TABLE evolution_reward_effort_processed (
                      submission_id INTEGER PRIMARY KEY REFERENCES evolution_submissions(id),
                      disposition TEXT NOT NULL CHECK(disposition IN('RECONCILED','INELIGIBLE'))
                    )
                    """);
            s.execute(
                    "CREATE INDEX evolution_reward_effort_processed_disposition ON"
                            + " evolution_reward_effort_processed(disposition,submission_id)");
        }
    }

    private record HistoricalEstimate(long revision, int skillVersion, EstimateInput input) {}
}
