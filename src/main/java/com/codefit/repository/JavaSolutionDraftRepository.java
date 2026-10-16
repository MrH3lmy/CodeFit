package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.JavaSolutionDraft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Persists each {@link com.codefit.model.Problem}'s single {@link JavaSolutionDraft}
 * ({@code UNIQUE(problem_id)}, #163) — surviving an application restart is the whole point of this
 * table, so the workspace's "autosave" is just calling {@link #save}/{@link #update} on every edit.
 */
public class JavaSolutionDraftRepository {

    public Optional<JavaSolutionDraft> findByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByProblemId(connection, problemId);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to access existing workspace", e);
        }
    }

    public Optional<JavaSolutionDraft> findByProblemId(Connection connection, long problemId)
            throws SQLException {
        String sql = "SELECT * FROM java_solution_drafts WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapDraft(resultSet)) : Optional.empty();
            }
        }
    }

    public JavaSolutionDraft save(JavaSolutionDraft draft) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, draft);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to access existing workspace", e);
        }
    }

    public JavaSolutionDraft save(Connection connection, JavaSolutionDraft draft)
            throws SQLException {
        String sql =
                "INSERT INTO java_solution_drafts (problem_id, main_class_name, source_code, stdin,"
                    + " expected_output) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, draft.getProblemId());
            statement.setString(2, draft.getMainClassName());
            statement.setString(3, draft.getSourceCode());
            statement.setString(4, draft.getStdin());
            statement.setString(5, draft.getExpectedOutput());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    draft.setId(keys.getLong(1));
                }
            }
            return findByProblemId(connection, draft.getProblemId()).orElseThrow();
        }
    }

    public void update(JavaSolutionDraft draft) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            update(connection, draft);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update Java solution draft", exception);
        }
    }

    /** Caller owns the transaction; used to save Evolution editor notes with the existing draft. */
    public void update(Connection connection, JavaSolutionDraft draft) throws SQLException {
        String sql = "UPDATE java_solution_drafts SET main_class_name = ?, source_code = ?, stdin = ?, "
                + "expected_output = ?, updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draft.getMainClassName());
            statement.setString(2, draft.getSourceCode());
            statement.setString(3, draft.getStdin());
            statement.setString(4, draft.getExpectedOutput());
            statement.setLong(5, draft.getProblemId());
            statement.executeUpdate();
        }
    }

    private JavaSolutionDraft mapDraft(ResultSet resultSet) throws SQLException {
        return new JavaSolutionDraft(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                resultSet.getString("main_class_name"),
                resultSet.getString("source_code"),
                resultSet.getString("stdin"),
                resultSet.getString("expected_output"),
                LocalDateTime.parse(resultSet.getString("updated_at").replace(' ', 'T'))
        );
    }
}
