package com.codefit.desktop;

import java.sql.*;
import java.util.*;

/**
 * Version 1 additive selection metadata, installed only on an exclusively owned disposable profile.
 */
final class PracticeGraphSchema {
    static void install(ExperimentalProfile profile) throws SQLException {
        var ddl = new LinkedHashMap<String, String>();
        ddl.put(
                "evolution_graph_selection_schema",
                "CREATE TABLE evolution_graph_selection_schema (version INTEGER PRIMARY KEY"
                    + " CHECK(version=1))");
        ddl.put(
                "evolution_graph_selection_tracks",
                "CREATE TABLE evolution_graph_selection_tracks (track_id TEXT PRIMARY KEY,domain_id"
                    + " TEXT NOT NULL,graph_id TEXT NOT NULL,graph_version INTEGER NOT NULL"
                    + " CHECK(graph_version>0),revision INTEGER NOT NULL CHECK(revision>=0),FOREIGN"
                    + " KEY(graph_id,graph_version) REFERENCES"
                    + " evolution_skill_graphs(graph_id,graph_version))");
        ddl.put(
                "evolution_graph_selection_receipts",
                "CREATE TABLE evolution_graph_selection_receipts (command_id TEXT PRIMARY"
                    + " KEY,profile_id TEXT NOT NULL,origin_session TEXT NOT NULL,track_id TEXT NOT"
                    + " NULL REFERENCES evolution_graph_selection_tracks(track_id),target_id TEXT"
                    + " NOT NULL,target_version INTEGER NOT NULL,expected_revision INTEGER NOT"
                    + " NULL,state TEXT NOT NULL CHECK(state IN"
                    + " ('SELECTED','UNCHANGED','REJECTED')),reason TEXT NOT NULL,selected_id TEXT"
                    + " NOT NULL,selected_version INTEGER NOT NULL,revision INTEGER NOT"
                    + " NULL,recorded_at TEXT NOT NULL)");
        ddl.put(
                "evolution_graph_selection_unique_revision",
                "CREATE UNIQUE INDEX evolution_graph_selection_unique_revision ON"
                    + " evolution_graph_selection_receipts(track_id,revision) WHERE"
                    + " state='SELECTED'");
        ddl.put(
                "evolution_graph_selection_lifecycle",
                "CREATE TABLE evolution_graph_selection_lifecycle (graph_id TEXT NOT"
                    + " NULL,graph_version INTEGER NOT NULL,revision INTEGER NOT NULL"
                    + " CHECK(revision>0),state TEXT NOT NULL CHECK(state IN"
                    + " ('ACTIVE','RETIRED')),replacement_id TEXT,replacement_version"
                    + " INTEGER,CHECK((replacement_id IS NULL)=(replacement_version IS"
                    + " NULL)),PRIMARY KEY(graph_id,graph_version,revision),FOREIGN"
                    + " KEY(graph_id,graph_version) REFERENCES"
                    + " evolution_skill_graphs(graph_id,graph_version),FOREIGN"
                    + " KEY(replacement_id,replacement_version) REFERENCES"
                    + " evolution_skill_graphs(graph_id,graph_version))");
        for (String table : List.of("schema", "receipts", "lifecycle"))
            for (String op : List.of("UPDATE", "DELETE")) {
                String full = "evolution_graph_selection_" + table,
                        name = full + "_no_" + op.toLowerCase(Locale.ROOT);
                ddl.put(
                        name,
                        "CREATE TRIGGER "
                                + name
                                + " BEFORE "
                                + op
                                + " ON "
                                + full
                                + " BEGIN SELECT RAISE(ABORT,'Immutable graph metadata'); END");
            }
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                try (var q = c.createStatement()) {
                    q.executeUpdate("UPDATE desktop_profile SET version=version");
                }
                var found = new LinkedHashMap<String, String>();
                try (var q = c.createStatement();
                        var r =
                                q.executeQuery(
                                        "SELECT name,sql FROM sqlite_master WHERE name LIKE"
                                            + " 'evolution_graph_selection_%'")) {
                    while (r.next()) found.put(r.getString(1), r.getString(2));
                }
                if (found.isEmpty())
                    try (var q = c.createStatement()) {
                        for (var sql : ddl.values()) q.execute(sql);
                        q.executeUpdate("INSERT INTO evolution_graph_selection_schema VALUES(1)");
                        q.executeUpdate(
                                "INSERT INTO evolution_graph_selection_tracks"
                                    + " VALUES('engineering-practice','software-engineering','java-debugging-concurrency',1,0)");
                    }
                else if (!found.equals(ddl)) throw new SQLException("PROFILE_INVALID");
                try (var q = c.createStatement();
                        var r =
                                q.executeQuery(
                                        "SELECT version FROM evolution_graph_selection_schema")) {
                    if (!r.next() || r.getInt(1) != 1 || r.next())
                        throw new SQLException("PROFILE_INVALID");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
