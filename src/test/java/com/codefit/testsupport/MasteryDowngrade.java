package com.codefit.testsupport;

import java.sql.*;

/** Isolated historical migration fixtures only. Never used by application code. */
public final class MasteryDowngrade {
    private MasteryDowngrade() {}

    public static void remove(Connection c) throws SQLException {
        AdaptationDowngrade.remove(c);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            for (String table :
                    new String[] {
                        "assessment_attempt_facts",
                        "evolution_baseline_choices",
                        "evolution_estimate_revisions",
                        "evolution_estimate_epochs"
                    }) s.execute("DROP TABLE IF EXISTS " + table);
            s.execute("DELETE FROM schema_migrations WHERE version=17");
        }
    }
}
