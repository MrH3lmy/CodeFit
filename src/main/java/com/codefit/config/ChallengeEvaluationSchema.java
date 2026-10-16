package com.codefit.config;

import java.sql.*;

/** Additive durable execution metadata. Attempts remain in their original source tables. */
final class ChallengeEvaluationSchema {
    private ChallengeEvaluationSchema() {}

    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    """
                    CREATE TABLE evolution_submissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL UNIQUE,
                        presentation_id TEXT NOT NULL REFERENCES evolution_challenge_presentations(id),
                        request_sha TEXT NOT NULL, snapshot BLOB NOT NULL CHECK(length(snapshot)<=2000000), snapshot_sha TEXT NOT NULL,
                        artifact_sha TEXT NOT NULL, executable_sha TEXT NOT NULL, family_id TEXT NOT NULL, variant_id TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN('PREPARED','RUNNING','EVALUATED','FINALIZED')),
                        execution_number INTEGER NOT NULL DEFAULT 0 CHECK(execution_number BETWEEN 0 AND 3),
                        run_token TEXT, owner_pid INTEGER, owner_started TEXT, cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN(0,1)),
                        child_pid INTEGER, child_started TEXT,
                        staged_run TEXT REFERENCES evolution_evaluation_runs(token), final_revision INTEGER,
                        problem_attempt_id INTEGER UNIQUE REFERENCES problem_attempts(id),
                        assessment_attempt_id INTEGER UNIQUE REFERENCES assessment_attempts(id),
                        FOREIGN KEY(id,final_revision) REFERENCES evolution_evaluation_revisions(submission_id,revision) DEFERRABLE INITIALLY DEFERRED,
                        CHECK((state='FINALIZED')=(final_revision IS NOT NULL)))
                    """);
            s.execute(
                    """
                    CREATE TABLE evolution_evaluation_runs (
                        token TEXT PRIMARY KEY, submission_id INTEGER NOT NULL REFERENCES evolution_submissions(id),
                        execution_number INTEGER NOT NULL, payload BLOB NOT NULL CHECK(length(payload)<=2000000), digest TEXT NOT NULL,
                        UNIQUE(submission_id,execution_number))
                    """);
            s.execute(
                    """
                    CREATE TABLE evolution_evaluation_revisions (
                        submission_id INTEGER NOT NULL REFERENCES evolution_submissions(id), revision INTEGER NOT NULL CHECK(revision>0),
                        correction_id TEXT NOT NULL UNIQUE, payload BLOB NOT NULL CHECK(length(payload)<=2000000), digest TEXT NOT NULL,
                        change_kind TEXT NOT NULL CHECK(change_kind IN('ORIGINAL','SUPERSESSION','RETRACTION')),
                        reason TEXT NOT NULL, actor TEXT NOT NULL, previous_evidence_id TEXT,
                        PRIMARY KEY(submission_id,revision))
                    """);
            s.execute(
                    """
                    CREATE TABLE evolution_evaluation_assessment_aliases (
                        assessment_attempt_id INTEGER PRIMARY KEY REFERENCES assessment_attempts(id),
                        submission_id INTEGER NOT NULL UNIQUE REFERENCES evolution_submissions(id))
                    """);
            s.execute(
                    "CREATE INDEX idx_evolution_submissions_recovery ON"
                        + " evolution_submissions(state,id)");
            s.execute(
                    "CREATE INDEX idx_evolution_submissions_family ON"
                        + " evolution_submissions(family_id,variant_id,id)");
            s.execute(
                    """
                    CREATE TRIGGER evolution_submission_snapshot_immutable BEFORE UPDATE ON evolution_submissions
                    WHEN NEW.id<>OLD.id OR NEW.command_id<>OLD.command_id OR NEW.presentation_id<>OLD.presentation_id
                      OR NEW.request_sha<>OLD.request_sha OR NEW.snapshot<>OLD.snapshot OR NEW.snapshot_sha<>OLD.snapshot_sha
                      OR NEW.artifact_sha<>OLD.artifact_sha OR NEW.executable_sha<>OLD.executable_sha
                      OR NEW.family_id<>OLD.family_id OR NEW.variant_id<>OLD.variant_id
                      OR (OLD.problem_attempt_id IS NOT NULL AND NEW.problem_attempt_id IS NOT OLD.problem_attempt_id)
                      OR (OLD.assessment_attempt_id IS NOT NULL AND NEW.assessment_attempt_id IS NOT OLD.assessment_attempt_id)
                    BEGIN SELECT RAISE(ABORT,'Frozen submission identity cannot change'); END
                    """);
            s.execute(
                    "CREATE TRIGGER evolution_submission_no_delete BEFORE DELETE ON"
                        + " evolution_submissions BEGIN SELECT RAISE(ABORT,'Submission history is"
                        + " immutable'); END");
            for (String table :
                    new String[] {
                        "evolution_evaluation_runs",
                        "evolution_evaluation_revisions",
                        "evolution_evaluation_assessment_aliases"
                    }) {
                s.execute(
                        "CREATE TRIGGER "
                                + table
                                + "_no_update BEFORE UPDATE ON "
                                + table
                                + " BEGIN SELECT RAISE(ABORT,'Evaluation history is immutable');"
                                + " END");
                s.execute(
                        "CREATE TRIGGER "
                                + table
                                + "_no_delete BEFORE DELETE ON "
                                + table
                                + " BEGIN SELECT RAISE(ABORT,'Evaluation history is immutable');"
                                + " END");
            }
        }
    }
}
