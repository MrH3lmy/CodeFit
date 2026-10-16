package com.codefit.config;

import java.sql.*;

/**
 * Local immutable catalog and append-only presentation metadata; no attempts or evaluation results.
 */
public final class QuestCatalogSchema {
    private QuestCatalogSchema() {}

    public static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_challenge_families (id TEXT PRIMARY KEY,"
                        + " cohort TEXT NOT NULL)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_challenge_rubrics (id TEXT NOT NULL,"
                        + " version INTEGER NOT NULL CHECK(version>0), payload TEXT NOT NULL,"
                        + " digest TEXT NOT NULL, PRIMARY KEY(id,version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_challenge_definitions (id TEXT NOT NULL,"
                        + " version INTEGER NOT NULL CHECK(version>0), family TEXT NOT NULL"
                        + " REFERENCES evolution_challenge_families(id), variant TEXT NOT NULL,"
                        + " cohort TEXT NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL,"
                        + " problem_id INTEGER UNIQUE REFERENCES problems(id), assessment_item_id"
                        + " INTEGER UNIQUE REFERENCES assessment_items(id), CHECK((problem_id IS"
                        + " NULL) <> (assessment_item_id IS NULL)), PRIMARY KEY(id,version),"
                        + " UNIQUE(family,variant,version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_quest_definitions (id TEXT NOT NULL,"
                        + " version INTEGER NOT NULL CHECK(version>0), payload TEXT NOT NULL,"
                        + " digest TEXT NOT NULL, PRIMARY KEY(id,version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_quest_candidates (quest_id TEXT NOT NULL,"
                        + " quest_version INTEGER NOT NULL, challenge_id TEXT NOT NULL,"
                        + " challenge_version INTEGER NOT NULL, PRIMARY"
                        + " KEY(quest_id,quest_version,challenge_id,challenge_version), FOREIGN"
                        + " KEY(quest_id,quest_version) REFERENCES"
                        + " evolution_quest_definitions(id,version), FOREIGN"
                        + " KEY(challenge_id,challenge_version) REFERENCES"
                        + " evolution_challenge_definitions(id,version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_assessment_reservations (item_id INTEGER"
                        + " PRIMARY KEY REFERENCES assessment_items(id), challenge_id TEXT NOT"
                        + " NULL, challenge_version INTEGER NOT NULL, FOREIGN"
                        + " KEY(challenge_id,challenge_version) REFERENCES"
                        + " evolution_challenge_definitions(id,version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_challenge_presentations (id TEXT PRIMARY"
                        + " KEY, quest_id TEXT NOT NULL, quest_version INTEGER NOT NULL,"
                        + " challenge_id TEXT NOT NULL, challenge_version INTEGER NOT NULL,"
                        + " presented_at TEXT NOT NULL, familiarity TEXT NOT NULL, prior_family"
                        + " INTEGER NOT NULL, prior_variant INTEGER NOT NULL, kind TEXT NOT NULL,"
                        + " prior_hint INTEGER NOT NULL, prior_solution INTEGER NOT NULL,"
                        + " prior_familiar INTEGER NOT NULL, FOREIGN"
                        + " KEY(quest_id,quest_version,challenge_id,challenge_version) REFERENCES"
                        + " evolution_quest_candidates(quest_id,quest_version,challenge_id,challenge_version))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS evolution_challenge_exposure_events (id TEXT"
                        + " PRIMARY KEY, presentation_id TEXT NOT NULL REFERENCES"
                        + " evolution_challenge_presentations(id), action TEXT NOT NULL, hint_level"
                        + " INTEGER NOT NULL, occurred_at TEXT NOT NULL)");
            for (String table : new String[] {"assessment_items", "assessment_variants"}) {
                String key = table.equals("assessment_items") ? "id" : "assessment_item_id";
                for (String operation : new String[] {"UPDATE"})
                    s.execute(
                            "CREATE TRIGGER IF NOT EXISTS evolution_reserved_"
                                    + table
                                    + "_"
                                    + operation
                                    + " BEFORE "
                                    + operation
                                    + " ON "
                                    + table
                                    + " WHEN EXISTS(SELECT 1 FROM evolution_assessment_reservations"
                                    + " WHERE item_id=OLD."
                                    + key
                                    + ") BEGIN SELECT RAISE(ABORT,'Reserved assessment content is"
                                    + " immutable'); END");
            }
            s.execute(
                    "CREATE INDEX IF NOT EXISTS idx_evolution_presentations_challenge ON"
                        + " evolution_challenge_presentations(challenge_id,challenge_version)");
            s.execute(
                    "CREATE INDEX IF NOT EXISTS idx_evolution_exposure_presentation ON"
                        + " evolution_challenge_exposure_events(presentation_id)");
            for (String table :
                    new String[] {
                        "evolution_challenge_families",
                        "evolution_challenge_rubrics",
                        "evolution_challenge_definitions",
                        "evolution_quest_definitions",
                        "evolution_quest_candidates",
                        "evolution_assessment_reservations",
                        "evolution_challenge_presentations",
                        "evolution_challenge_exposure_events"
                    }) {
                s.execute(
                        "CREATE TRIGGER IF NOT EXISTS "
                                + table
                                + "_no_update BEFORE UPDATE ON "
                                + table
                                + " BEGIN SELECT RAISE(ABORT,'Evolution history is immutable');"
                                + " END");
                s.execute(
                        "CREATE TRIGGER IF NOT EXISTS "
                                + table
                                + "_no_delete BEFORE DELETE ON "
                                + table
                                + " BEGIN SELECT RAISE(ABORT,'Evolution history is immutable');"
                                + " END");
            }
            s.execute(
                    "CREATE TRIGGER IF NOT EXISTS evolution_problem_immutable BEFORE UPDATE ON"
                        + " problems WHEN OLD.platform='CODEFIT_EVOLUTION' OR"
                        + " NEW.platform='CODEFIT_EVOLUTION' BEGIN SELECT RAISE(ABORT,'Evolution"
                        + " problem binding is immutable'); END");
            s.execute(
                    "CREATE TRIGGER IF NOT EXISTS evolution_roadmap_exclusion BEFORE INSERT ON"
                        + " roadmap_entries WHEN EXISTS(SELECT 1 FROM problems WHERE"
                        + " id=NEW.problem_id AND platform='CODEFIT_EVOLUTION') BEGIN SELECT"
                        + " RAISE(ABORT,'Evolution content is outside the workbook'); END");
        }
    }
}
