package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.SolvingPhase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Persists each {@link com.codefit.model.Problem}'s single, resumable {@link ProblemSolvingSession}
 * row ({@code UNIQUE(problem_id)}). Kept in its own table so the workspace's live phase-timer state
 * never has to be reconstructed from, or confused with, {@code problem_attempts}' finalized
 * submission history.
 */
public class ProblemSolvingSessionRepository {

    public Optional<ProblemSolvingSession> findByProblemId(long problemId) {
        String sql = "SELECT * FROM problem_solving_sessions WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapSession(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem solving session", exception);
        }
    }

    public ProblemSolvingSession save(ProblemSolvingSession session) {
        String sql = "INSERT INTO problem_solving_sessions (problem_id, phase, reading_seconds_elapsed, "
                + "thinking_seconds_elapsed, coding_seconds_elapsed, debugging_seconds_elapsed, notes, active) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsertFields(statement, session);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    session.setId(keys.getLong(1));
                }
            }
            return findByProblemId(session.getProblemId()).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem solving session", exception);
        }
    }

    public void update(ProblemSolvingSession session) {
        String sql = "UPDATE problem_solving_sessions SET phase = ?, reading_seconds_elapsed = ?, "
                + "thinking_seconds_elapsed = ?, coding_seconds_elapsed = ?, debugging_seconds_elapsed = ?, "
                + "notes = ?, active = ?, last_active_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.getPhase().name());
            statement.setInt(2, session.getReadingSecondsElapsed());
            statement.setInt(3, session.getThinkingSecondsElapsed());
            statement.setInt(4, session.getCodingSecondsElapsed());
            statement.setInt(5, session.getDebuggingSecondsElapsed());
            statement.setString(6, session.getNotes());
            statement.setInt(7, session.isActive() ? 1 : 0);
            statement.setLong(8, session.getProblemId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem solving session", exception);
        }
    }

    public void deleteByProblemId(long problemId) {
        String sql = "DELETE FROM problem_solving_sessions WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to delete problem solving session", exception);
        }
    }

    private void bindInsertFields(PreparedStatement statement, ProblemSolvingSession session) throws SQLException {
        statement.setLong(1, session.getProblemId());
        statement.setString(2, session.getPhase().name());
        statement.setInt(3, session.getReadingSecondsElapsed());
        statement.setInt(4, session.getThinkingSecondsElapsed());
        statement.setInt(5, session.getCodingSecondsElapsed());
        statement.setInt(6, session.getDebuggingSecondsElapsed());
        statement.setString(7, session.getNotes());
        statement.setInt(8, session.isActive() ? 1 : 0);
    }

    private ProblemSolvingSession mapSession(ResultSet resultSet) throws SQLException {
        return new ProblemSolvingSession(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                SolvingPhase.valueOf(resultSet.getString("phase")),
                resultSet.getInt("reading_seconds_elapsed"),
                resultSet.getInt("thinking_seconds_elapsed"),
                resultSet.getInt("coding_seconds_elapsed"),
                resultSet.getInt("debugging_seconds_elapsed"),
                resultSet.getString("notes"),
                resultSet.getInt("active") == 1,
                LocalDateTime.parse(resultSet.getString("started_at").replace(' ', 'T')),
                LocalDateTime.parse(resultSet.getString("last_active_at").replace(' ', 'T'))
        );
    }
}
