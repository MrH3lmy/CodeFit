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
        String sql = "SELECT * FROM java_solution_drafts WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapDraft(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load Java solution draft", exception);
        }
    }

    public JavaSolutionDraft save(JavaSolutionDraft draft) {
        String sql = "INSERT INTO java_solution_drafts (problem_id, main_class_name, source_code, stdin, expected_output) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            return findByProblemId(draft.getProblemId()).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save Java solution draft", exception);
        }
    }

    public void update(JavaSolutionDraft draft) {
        String sql = "UPDATE java_solution_drafts SET main_class_name = ?, source_code = ?, stdin = ?, "
                + "expected_output = ?, updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draft.getMainClassName());
            statement.setString(2, draft.getSourceCode());
            statement.setString(3, draft.getStdin());
            statement.setString(4, draft.getExpectedOutput());
            statement.setLong(5, draft.getProblemId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update Java solution draft", exception);
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
