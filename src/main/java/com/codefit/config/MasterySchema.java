package com.codefit.config;

import java.sql.*;

/** Additive local interpretation/choice metadata only. No history backfill or reward writes. */
final class MasterySchema {
    private MasterySchema() {}

    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_estimate_epochs (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                      graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL, started_at TEXT NOT NULL,
                      evidence_through INTEGER NOT NULL CHECK(evidence_through>=0),
                      FOREIGN KEY(graph_id,graph_version) REFERENCES evolution_skill_graphs(graph_id,graph_version))
                    """);
            s.execute(
                    """
                    CREATE TABLE evolution_estimate_revisions (
                      revision INTEGER PRIMARY KEY AUTOINCREMENT, input_sha TEXT NOT NULL,
                      policy TEXT NOT NULL, graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL,
                      skill_id TEXT NOT NULL, skill_version INTEGER NOT NULL, epoch_id TEXT NOT NULL,
                      as_of TEXT NOT NULL, inputs BLOB NOT NULL, UNIQUE(policy,input_sha),
                      FOREIGN KEY(graph_id,graph_version) REFERENCES evolution_skill_graphs(graph_id,graph_version),
                      FOREIGN KEY(skill_id,skill_version) REFERENCES evolution_skill_definitions(skill_id,definition_version))
                    """);
            s.execute(
                    "CREATE INDEX evolution_estimate_history ON"
                        + " evolution_estimate_revisions(graph_id,graph_version,skill_id,skill_version,revision)");
            s.execute(
                    """
                    CREATE TABLE evolution_baseline_choices (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL UNIQUE,
                      graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL, task TEXT NOT NULL,
                      action TEXT NOT NULL CHECK(action IN ('SKIP','RESUME')), chosen_at TEXT NOT NULL,
                      FOREIGN KEY(graph_id,graph_version) REFERENCES evolution_skill_graphs(graph_id,graph_version))
                    """);
            s.execute(
                    """
                    CREATE TABLE assessment_attempt_facts (
                      attempt_id INTEGER PRIMARY KEY REFERENCES assessment_attempts(id),
                      grade_kind TEXT NOT NULL, validator TEXT NOT NULL, grading_version TEXT,
                      variant_id INTEGER NOT NULL, variant_index INTEGER NOT NULL, variant_sha TEXT NOT NULL,
                      presentation_id TEXT NOT NULL, presented_at TEXT NOT NULL, submitted_at TEXT NOT NULL,
                      hint_used INTEGER NOT NULL, solution_revealed INTEGER NOT NULL,
                      prior_item_attempts INTEGER NOT NULL, exposure TEXT NOT NULL)
                    """);
            for (String t :
                    new String[] {
                        "evolution_estimate_epochs",
                        "evolution_estimate_revisions",
                        "evolution_baseline_choices",
                        "assessment_attempt_facts"
                    }) {
                s.execute(
                        "CREATE TRIGGER "
                                + t
                                + "_immutable_update BEFORE UPDATE ON "
                                + t
                                + " BEGIN SELECT RAISE(ABORT,'Immutable interpretation history');"
                                + " END");
                s.execute(
                        "CREATE TRIGGER "
                                + t
                                + "_immutable_delete BEFORE DELETE ON "
                                + t
                                + " BEGIN SELECT RAISE(ABORT,'Immutable interpretation history');"
                                + " END");
            }
        }
    }
}
