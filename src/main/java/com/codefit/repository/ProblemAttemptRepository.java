package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SubmissionResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the many {@link ProblemAttempt} rows a single {@link com.codefit.model.Problem} can have.
 * {@code UNIQUE(problem_id, attempt_number)} keeps a replayed import (or a retried save) from ever
 * duplicating an attempt; {@code ProblemAttemptService} is responsible for computing the next
 * attempt number from {@link #countByProblemId(long)} before calling {@link #save(ProblemAttempt)}.
 */
public class ProblemAttemptRepository {

    public List<ProblemAttempt> findByProblemId(long problemId) {
        String sql = "SELECT * FROM problem_attempts WHERE problem_id = ? ORDER BY attempt_number";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem attempts", exception);
        }
    }

    public int countByProblemId(long problemId) {
        String sql = "SELECT COUNT(*) FROM problem_attempts WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count problem attempts", exception);
        }
    }

    public ProblemAttempt save(ProblemAttempt attempt) {
        String sql = "INSERT INTO problem_attempts (problem_id, attempt_number, submission_result, "
                + "reading_time_seconds, thinking_time_seconds, coding_time_seconds, debugging_time_seconds, notes, session_outcome) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, attempt.problemId());
            statement.setInt(2, attempt.attemptNumber());
            statement.setString(3, attempt.submissionResult().name());
            setNullableInt(statement, 4, attempt.readingTimeSeconds());
            setNullableInt(statement, 5, attempt.thinkingTimeSeconds());
            setNullableInt(statement, 6, attempt.codingTimeSeconds());
            setNullableInt(statement, 7, attempt.debuggingTimeSeconds());
            statement.setString(8, attempt.notes());
            statement.setString(9, attempt.sessionOutcome() == null ? null : attempt.sessionOutcome().name());
            statement.executeUpdate();
            long id = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new ProblemAttempt(id, attempt.problemId(), attempt.attemptNumber(), attempt.submissionResult(),
                    attempt.readingTimeSeconds(), attempt.thinkingTimeSeconds(), attempt.codingTimeSeconds(),
                    attempt.debuggingTimeSeconds(), attempt.submittedAt(), attempt.notes(), attempt.sessionOutcome());
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem attempt", exception);
        }
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private List<ProblemAttempt> mapAll(ResultSet resultSet) throws SQLException {
        List<ProblemAttempt> attempts = new ArrayList<>();
        while (resultSet.next()) {
            String sessionOutcome = resultSet.getString("session_outcome");
            attempts.add(new ProblemAttempt(
                    resultSet.getLong("id"),
                    resultSet.getLong("problem_id"),
                    resultSet.getInt("attempt_number"),
                    SubmissionResult.valueOf(resultSet.getString("submission_result")),
                    nullableInteger(resultSet, "reading_time_seconds"),
                    nullableInteger(resultSet, "thinking_time_seconds"),
                    nullableInteger(resultSet, "coding_time_seconds"),
                    nullableInteger(resultSet, "debugging_time_seconds"),
                    LocalDateTime.parse(resultSet.getString("submitted_at").replace(' ', 'T')),
                    resultSet.getString("notes"),
                    sessionOutcome == null ? null : SessionFinishOutcome.valueOf(sessionOutcome)));
        }
        return attempts;
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
