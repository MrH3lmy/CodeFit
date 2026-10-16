package com.codefit.config;

import java.sql.*;

/** Local immutable decisions and explicit learner commands; never a shared activity stream. */
final class AdaptationSchema {
    private AdaptationSchema() {}

    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_adaptation_decisions (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                      input_sha TEXT NOT NULL, policy TEXT NOT NULL, configuration TEXT NOT NULL,
                      graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL,
                      preferences_version INTEGER NOT NULL, as_of TEXT NOT NULL,
                      inputs BLOB NOT NULL, output_sha TEXT NOT NULL, outputs BLOB NOT NULL,
                      UNIQUE(policy,configuration,input_sha))
                    """);
            s.execute(
                    "CREATE INDEX evolution_adaptation_history ON"
                            + " evolution_adaptation_decisions(sequence)");
            s.execute(
                    """
                    CREATE TABLE evolution_adaptation_choices (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL UNIQUE,
                      payload_sha TEXT NOT NULL, payload BLOB NOT NULL,
                      decision_id TEXT REFERENCES evolution_adaptation_decisions(id),
                      chosen_at TEXT NOT NULL, graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL,
                      preferences_sha TEXT NOT NULL, preferences BLOB NOT NULL)
                    """);
            for (var t :
                    new String[] {
                        "evolution_adaptation_decisions", "evolution_adaptation_choices"
                    }) {
                s.execute(
                        "CREATE TRIGGER "
                                + t
                                + "_immutable_update BEFORE UPDATE ON "
                                + t
                                + " BEGIN SELECT RAISE(ABORT,'Immutable adaptation history'); END");
                s.execute(
                        "CREATE TRIGGER "
                                + t
                                + "_immutable_delete BEFORE DELETE ON "
                                + t
                                + " BEGIN SELECT RAISE(ABORT,'Immutable adaptation history'); END");
            }
        }
    }
}
