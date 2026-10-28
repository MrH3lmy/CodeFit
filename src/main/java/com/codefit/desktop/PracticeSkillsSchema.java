package com.codefit.desktop;

import java.sql.*;
import java.util.*;

/** Additive metadata on the locked disposable profile only. No learner migration. */
final class PracticeSkillsSchema {
    static void install(ExperimentalProfile profile) throws SQLException {
        var ddl = new LinkedHashMap<String, String>();
        ddl.put(
                "desktop_skill_commands",
                "CREATE TABLE desktop_skill_commands (command_id TEXT PRIMARY KEY, origin_session"
                    + " TEXT NOT NULL, profile_id TEXT NOT NULL, as_of TEXT NOT NULL, policy TEXT"
                    + " NOT NULL)");
        ddl.put(
                "desktop_skill_receipts",
                "CREATE TABLE desktop_skill_receipts (command_id TEXT PRIMARY KEY REFERENCES"
                    + " desktop_skill_commands(command_id), state TEXT NOT NULL CHECK(state IN"
                    + " ('PUBLISHED','REJECTED')), reason TEXT NOT NULL, input_sha TEXT NOT NULL)");
        ddl.put(
                "desktop_skill_results",
                "CREATE TABLE desktop_skill_results (command_id TEXT NOT NULL REFERENCES"
                    + " desktop_skill_commands(command_id), skill_id TEXT NOT NULL, revision"
                    + " INTEGER NOT NULL REFERENCES evolution_estimate_revisions(revision), created"
                    + " INTEGER NOT NULL CHECK(created IN (0,1)), PRIMARY"
                    + " KEY(command_id,skill_id))");
        for (String table : List.copyOf(ddl.keySet()))
            for (String op : List.of("UPDATE", "DELETE")) {
                String name = table + "_no_" + op.toLowerCase(Locale.ROOT);
                ddl.put(
                        name,
                        "CREATE TRIGGER "
                                + name
                                + " BEFORE "
                                + op
                                + " ON "
                                + table
                                + " BEGIN SELECT RAISE(ABORT,'Immutable skill command'); END");
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
                                            + " 'desktop_skill_%'")) {
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
