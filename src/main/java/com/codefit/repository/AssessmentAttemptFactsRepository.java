package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.AssessmentAttemptFacts;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/** Shares the source attempt's transaction. Never copies submitted answers or reference keys. */
public final class AssessmentAttemptFactsRepository {
    public void append(Connection c, long attempt, AssessmentAttemptFacts f) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Source transaction required");
        try (var s =
                c.prepareStatement(
                        "INSERT INTO assessment_attempt_facts"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setLong(1, attempt);
            s.setString(2, f.gradeKind());
            s.setString(3, f.validator());
            s.setString(4, f.gradingVersion());
            s.setLong(5, f.variantId());
            s.setInt(6, f.variantIndex());
            s.setString(7, f.variantSha());
            s.setString(8, f.presentationId());
            s.setString(9, f.presentedAt().toString());
            s.setString(10, f.submittedAt().toString());
            s.setInt(11, f.hintUsed() ? 1 : 0);
            s.setInt(12, f.solutionRevealed() ? 1 : 0);
            s.setInt(13, f.priorItemAttempts());
            s.setString(14, f.exposure());
            s.executeUpdate();
        }
    }

    public Optional<AssessmentAttemptFacts> find(long attempt) {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                "SELECT * FROM assessment_attempt_facts WHERE attempt_id=?")) {
            s.setLong(1, attempt);
            try (var r = s.executeQuery()) {
                return r.next()
                        ? Optional.of(
                                new AssessmentAttemptFacts(
                                        r.getString("grade_kind"),
                                        r.getString("validator"),
                                        r.getString("grading_version"),
                                        r.getLong("variant_id"),
                                        r.getInt("variant_index"),
                                        r.getString("variant_sha"),
                                        r.getString("presentation_id"),
                                        Instant.parse(r.getString("presented_at")),
                                        Instant.parse(r.getString("submitted_at")),
                                        r.getInt("hint_used") != 0,
                                        r.getInt("solution_revealed") != 0,
                                        r.getInt("prior_item_attempts"),
                                        r.getString("exposure")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read assessment facts", e);
        }
    }
}
