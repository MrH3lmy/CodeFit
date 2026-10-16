package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.AssessmentAttempt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists {@link AssessmentAttempt} rows in their own table, entirely separate from
 * {@code review_history}. No method here ever touches {@code flashcards} or {@code review_history}
 * — that isolation is what keeps assessment results from silently altering normal spaced-repetition
 * scheduling (#104).
 */
public class AssessmentAttemptRepository {

    public AssessmentAttempt save(AssessmentAttempt attempt) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, attempt);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save assessment attempt", exception);
        }
    }

    /** Caller owns the transaction, including the authoritative Evolution evaluation and alias. */
    public AssessmentAttempt save(Connection connection, AssessmentAttempt attempt) throws SQLException {
        String sql = "INSERT INTO assessment_attempts (assessment_item_id, variant_index, skill_category, "
                + "module_name, correct, submitted_answer, response_time_ms, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, attempt.assessmentItemId());
            statement.setInt(2, attempt.variantIndex());
            statement.setString(3, attempt.skillCategory());
            statement.setString(4, attempt.moduleName());
            statement.setInt(5, attempt.correct() ? 1 : 0);
            statement.setString(6, attempt.submittedAnswer());
            if (attempt.responseTimeMs() == null) {
                statement.setNull(7, Types.INTEGER);
            } else {
                statement.setInt(7, attempt.responseTimeMs());
            }
            statement.setString(8, attempt.runId());
            statement.executeUpdate();
            long id = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new AssessmentAttempt(id, attempt.assessmentItemId(), attempt.variantIndex(), attempt.skillCategory(),
                    attempt.moduleName(), attempt.correct(), attempt.submittedAnswer(), attempt.responseTimeMs(),
                    attempt.attemptedAt(), attempt.runId());
        }
    }

    public List<AssessmentAttempt> findRecent(int limit) {
        String sql = "SELECT * FROM assessment_attempts ORDER BY attempted_at DESC, id DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load assessment attempts", exception);
        }
    }

    /** Existing weekly reports must not incorporate reserved Evolution diagnostics. */
    public List<AssessmentAttempt> findRecentOrdinary(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Bounded history required");
        String sql = "SELECT * FROM assessment_attempts a WHERE NOT EXISTS (SELECT 1 FROM evolution_assessment_reservations r WHERE r.item_id=a.assessment_item_id) AND NOT EXISTS (SELECT 1 FROM evolution_evaluation_assessment_aliases e WHERE e.assessment_attempt_id=a.id) ORDER BY attempted_at DESC,id DESC LIMIT ?";
        try (Connection c = DatabaseConfig.getConnection(); var s = c.prepareStatement(sql)) {
            s.setInt(1, limit);
            try (var rows = s.executeQuery()) { return mapAll(rows); }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load ordinary assessment history", e);
        }
    }

    /** How many times each assessment item has already been attempted, used to rotate which variant is served next. */
    public Map<Long, Integer> countByItemId() {
        String sql = "SELECT assessment_item_id, COUNT(*) AS attempt_count FROM assessment_attempts GROUP BY assessment_item_id";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, Integer> counts = new HashMap<>();
            while (resultSet.next()) {
                counts.put(resultSet.getLong("assessment_item_id"), resultSet.getInt("attempt_count"));
            }
            return counts;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load assessment attempt counts", exception);
        }
    }

    private List<AssessmentAttempt> mapAll(ResultSet resultSet) throws SQLException {
        List<AssessmentAttempt> attempts = new ArrayList<>();
        while (resultSet.next()) {
            attempts.add(new AssessmentAttempt(
                    resultSet.getLong("id"),
                    resultSet.getLong("assessment_item_id"),
                    resultSet.getInt("variant_index"),
                    resultSet.getString("skill_category"),
                    resultSet.getString("module_name"),
                    resultSet.getInt("correct") == 1,
                    resultSet.getString("submitted_answer"),
                    nullableInteger(resultSet, "response_time_ms"),
                    LocalDateTime.parse(resultSet.getString("attempted_at").replace(' ', 'T')),
                    resultSet.getString("run_id")));
        }
        return attempts;
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
