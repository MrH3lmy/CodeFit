package com.codefit.config;

import java.sql.*;

/** Mutable local editor notes, never attempts, evidence, events or peer data. */
final class PlayerWorkspaceSchema {
    private PlayerWorkspaceSchema() {}

    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    """
CREATE TABLE evolution_working_notes (
    presentation_id TEXT PRIMARY KEY REFERENCES evolution_challenge_presentations(id),
    response TEXT CHECK(response IS NULL OR length(CAST(response AS BLOB))<=200000),
    reflection TEXT NOT NULL CHECK(length(reflection)<=16000),
    assistance TEXT NOT NULL CHECK(assistance IN ('UNKNOWN','NONE_DECLARED','DECLARED')),
    updated_at TEXT NOT NULL)
""");
        }
    }
}
