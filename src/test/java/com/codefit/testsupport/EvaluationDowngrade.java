package com.codefit.testsupport;

import java.sql.*;

/** Removes migration 16 only, exclusively from isolated historical-schema test fixtures. */
public final class EvaluationDowngrade {
    private EvaluationDowngrade() {}

    public static void remove(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            for (String t :
                    new String[] {
                        "evolution_evaluation_assessment_aliases",
                        "evolution_evaluation_revisions",
                        "evolution_evaluation_runs",
                        "evolution_submissions"
                    }) s.execute("DROP TABLE IF EXISTS " + t);
            s.execute("DELETE FROM schema_migrations WHERE version=16");
        }
    }
}
