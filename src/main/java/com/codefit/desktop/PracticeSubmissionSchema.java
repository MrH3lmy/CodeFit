package com.codefit.desktop;

import java.sql.*;
import java.util.*;

/** Immutable correlation metadata only; all artifacts/stages stay in the existing evaluator. */
final class PracticeSubmissionSchema {
    static void install(ExperimentalProfile profile) throws SQLException {
        var ddl = new LinkedHashMap<String, String>();
        ddl.put(
                "desktop_submission_commands",
                "CREATE TABLE desktop_submission_commands (command_id TEXT PRIMARY KEY REFERENCES"
                    + " evolution_submissions(command_id), presentation_id TEXT NOT NULL REFERENCES"
                    + " evolution_challenge_presentations(id), draft_revision INTEGER NOT NULL"
                    + " CHECK(draft_revision>=0 AND draft_revision<9007199254740000), assistance"
                    + " TEXT NOT NULL CHECK(assistance IN"
                    + " ('UNKNOWN','NONE_DECLARED','DECLARED')))");
        for (String op : List.of("UPDATE", "DELETE")) {
            String name = "desktop_submission_no_" + op.toLowerCase(Locale.ROOT);
            ddl.put(
                    name,
                    "CREATE TRIGGER "
                            + name
                            + " BEFORE "
                            + op
                            + " ON desktop_submission_commands BEGIN SELECT RAISE(ABORT,'Immutable"
                            + " submission receipt'); END");
        }
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                try (var s = c.createStatement()) {
                    s.executeUpdate("UPDATE desktop_profile SET version=version");
                }
                var found = new LinkedHashMap<String, String>();
                try (var s = c.createStatement();
                        var r =
                                s.executeQuery(
                                        "SELECT name,sql FROM sqlite_master WHERE name LIKE"
                                            + " 'desktop_submission_%'")) {
                    while (r.next()) found.put(r.getString(1), r.getString(2));
                }
                if (found.isEmpty()) {
                    try (var s = c.createStatement()) {
                        for (var sql : ddl.values()) s.execute(sql);
                    }
                } else if (!found.equals(ddl)) throw new SQLException("PROFILE_INVALID");
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
