package com.codefit.desktop;

import java.sql.*;
import java.util.*;

/** Only the exclusively owned, verified disposable learning profile may install this metadata. */
final class PracticeDraftSchema {
    private static Map<String, String> definitions() {
        var ddl = new LinkedHashMap<String, String>();
        ddl.put(
                "desktop_draft_clock",
                "CREATE TABLE desktop_draft_clock (id INTEGER PRIMARY KEY CHECK(id=1), revision"
                    + " INTEGER NOT NULL CHECK(revision>=0 AND revision<9007199254740000))");
        ddl.put(
                "desktop_draft_saves",
                "CREATE TABLE desktop_draft_saves (command_id TEXT PRIMARY KEY, presentation_id"
                    + " TEXT NOT NULL REFERENCES evolution_challenge_presentations(id), digest TEXT"
                    + " NOT NULL CHECK(length(digest)=64), expected_revision INTEGER NOT NULL,"
                    + " revision INTEGER NOT NULL, outcome TEXT NOT NULL CHECK(outcome IN"
                    + " ('SAVED','CONFLICT')))");
        for (String table : List.of("java_solution_drafts", "evolution_working_notes"))
            for (String op : List.of("INSERT", "UPDATE", "DELETE")) {
                String name = "desktop_revision_" + table + "_" + op.toLowerCase(Locale.ROOT);
                ddl.put(
                        name,
                        "CREATE TRIGGER "
                                + name
                                + " AFTER "
                                + op
                                + " ON "
                                + table
                                + " BEGIN UPDATE desktop_draft_clock SET revision=revision+1 WHERE"
                                + " id=1; END");
            }
        for (String op : List.of("UPDATE", "DELETE")) {
            String name = "desktop_draft_saves_no_" + op.toLowerCase(Locale.ROOT);
            ddl.put(
                    name,
                    "CREATE TRIGGER "
                            + name
                            + " BEFORE "
                            + op
                            + " ON desktop_draft_saves BEGIN SELECT RAISE(ABORT,'Immutable draft"
                            + " receipt'); END");
        }
        return ddl;
    }

    static void install(ExperimentalProfile profile) throws SQLException {
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                // Serializes with any cooperating owner; profile OS lock already held.
                try (var s = c.createStatement()) {
                    s.executeUpdate("UPDATE desktop_profile SET version=version");
                }
                var found = new HashMap<String, String>();
                try (var s = c.createStatement();
                        var r =
                                s.executeQuery(
                                        "SELECT name,sql FROM sqlite_master WHERE name LIKE"
                                            + " 'desktop_draft_%' OR name LIKE"
                                            + " 'desktop_revision_%'")) {
                    while (r.next()) found.put(r.getString(1), r.getString(2));
                }
                var expected = definitions();
                if (found.isEmpty()) {
                    try (var s = c.createStatement()) {
                        for (var sql : expected.values()) s.execute(sql);
                        s.executeUpdate("INSERT INTO desktop_draft_clock VALUES (1,0)");
                    }
                } else if (!found.equals(expected)) throw new SQLException("PROFILE_INVALID");
                try (var s = c.createStatement();
                        var r = s.executeQuery("SELECT id,revision FROM desktop_draft_clock")) {
                    if (!r.next() || r.getInt(1) != 1 || r.getLong(2) < 0 || r.next())
                        throw new SQLException("PROFILE_INVALID");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static long revision(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT revision FROM desktop_draft_clock WHERE id=1")) {
            if (!r.next()) throw new SQLException("PROFILE_INVALID");
            return r.getLong(1);
        }
    }
}
