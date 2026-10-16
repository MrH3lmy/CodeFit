package com.codefit.testsupport;

import java.sql.*;
import java.util.*;

/** Removes only later additive schema in throwaway old-version migration fixtures. */
public final class QuestCatalogDowngrade {
    private QuestCatalogDowngrade() {}

    public static void remove(Connection c) throws SQLException {
        EvaluationDowngrade.remove(c);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            List<String> tables = new ArrayList<>();
            try (var rs =
                    s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE"
                                + " 'evolution_challenge_%' OR name LIKE 'evolution_quest_%' OR"
                                + " name='evolution_assessment_reservations')")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            for (String table : tables) s.execute("DROP TABLE " + table);
            s.execute("DROP TRIGGER IF EXISTS evolution_reserved_assessment_items_UPDATE");
            s.execute("DROP TRIGGER IF EXISTS evolution_reserved_assessment_variants_UPDATE");
            s.execute("DROP TRIGGER IF EXISTS evolution_problem_immutable");
            s.execute("DROP TRIGGER IF EXISTS evolution_roadmap_exclusion");
            s.execute("DELETE FROM schema_migrations WHERE version=15");
        }
    }
}
