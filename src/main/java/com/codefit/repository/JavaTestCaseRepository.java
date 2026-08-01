package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.JavaTestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists a problem's ordered {@link JavaTestCase} list (#163) — a genuine one-to-many table, unlike
 * {@link JavaSolutionDraftRepository}'s single row per problem.
 */
public class JavaTestCaseRepository {

    public List<JavaTestCase> findByProblemId(long problemId) {
        String sql = "SELECT * FROM java_test_cases WHERE problem_id = ? ORDER BY position ASC, id ASC";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<JavaTestCase> testCases = new ArrayList<>();
                while (resultSet.next()) {
                    testCases.add(mapTestCase(resultSet));
                }
                return testCases;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load Java test cases", exception);
        }
    }

    public int countByProblemId(long problemId) {
        String sql = "SELECT COUNT(*) FROM java_test_cases WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count Java test cases", exception);
        }
    }

    public JavaTestCase save(JavaTestCase testCase) {
        String sql = "INSERT INTO java_test_cases (problem_id, position, stdin, expected_output) VALUES (?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, testCase.getProblemId());
            statement.setInt(2, testCase.getPosition());
            statement.setString(3, testCase.getStdin());
            statement.setString(4, testCase.getExpectedOutput());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    testCase.setId(keys.getLong(1));
                }
            }
            return testCase;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save Java test case", exception);
        }
    }

    public void update(JavaTestCase testCase) {
        String sql = "UPDATE java_test_cases SET stdin = ?, expected_output = ? WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, testCase.getStdin());
            statement.setString(2, testCase.getExpectedOutput());
            statement.setLong(3, testCase.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update Java test case", exception);
        }
    }

    public void deleteById(long id) {
        String sql = "DELETE FROM java_test_cases WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to delete Java test case", exception);
        }
    }

    private JavaTestCase mapTestCase(ResultSet resultSet) throws SQLException {
        return new JavaTestCase(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                resultSet.getInt("position"),
                resultSet.getString("stdin"),
                resultSet.getString("expected_output")
        );
    }
}
