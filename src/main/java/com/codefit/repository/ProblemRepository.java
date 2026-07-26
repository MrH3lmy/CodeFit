package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link Problem} identity rows. Entirely separate from {@link FlashcardRepository}: no
 * method here ever touches {@code flashcards} or {@code decks} (#142).
 */
public class ProblemRepository {

    public List<Problem> findAll() {
        return query("SELECT * FROM problems ORDER BY title");
    }

    public Optional<Problem> findById(long id) {
        String sql = "SELECT * FROM problems WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProblem(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem", exception);
        }
    }

    /** The natural key used to keep repeated workbook imports from creating duplicate problems. */
    public Optional<Problem> findByPlatformAndExternalCode(String platform, String externalCode) {
        String sql = "SELECT * FROM problems WHERE platform = ? AND external_code = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, platform);
            statement.setString(2, externalCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProblem(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem by natural key", exception);
        }
    }

    public List<Problem> findByTopic(String topic) {
        String sql = "SELECT * FROM problems WHERE lower(topic) = lower(?) ORDER BY title";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problems by topic", exception);
        }
    }

    public Problem save(Problem problem) {
        String sql = "INSERT INTO problems (external_code, platform, title, url, topic, quality_rating, learning_resources) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, problem.getExternalCode());
            statement.setString(2, problem.getPlatform());
            statement.setString(3, problem.getTitle());
            statement.setString(4, problem.getUrl());
            statement.setString(5, problem.getTopic());
            if (problem.getQualityRating() == null) {
                statement.setNull(6, Types.INTEGER);
            } else {
                statement.setInt(6, problem.getQualityRating());
            }
            statement.setString(7, problem.getLearningResources());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    problem.setId(keys.getLong(1));
                }
            }
            return findById(problem.getId()).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem", exception);
        }
    }

    /** Updates the catalog fields of an existing problem; identity (platform, externalCode) never changes. */
    public void update(Problem problem) {
        String sql = "UPDATE problems SET title = ?, url = ?, topic = ?, quality_rating = ?, learning_resources = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, problem.getTitle());
            statement.setString(2, problem.getUrl());
            statement.setString(3, problem.getTopic());
            if (problem.getQualityRating() == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, problem.getQualityRating());
            }
            statement.setString(5, problem.getLearningResources());
            statement.setLong(6, problem.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem", exception);
        }
    }

    public int countAll() {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM problems");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count problems", exception);
        }
    }

    private List<Problem> query(String sql) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problems", exception);
        }
    }

    private List<Problem> mapAll(ResultSet resultSet) throws SQLException {
        List<Problem> problems = new ArrayList<>();
        while (resultSet.next()) {
            problems.add(mapProblem(resultSet));
        }
        return problems;
    }

    private Problem mapProblem(ResultSet resultSet) throws SQLException {
        return new Problem(
                resultSet.getLong("id"),
                resultSet.getString("external_code"),
                resultSet.getString("platform"),
                resultSet.getString("title"),
                resultSet.getString("url"),
                resultSet.getString("topic"),
                nullableInteger(resultSet, "quality_rating"),
                resultSet.getString("learning_resources"),
                parseDateTime(resultSet.getString("created_at")),
                parseDateTime(resultSet.getString("updated_at"))
        );
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime parseDateTime(String value) {
        return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T'));
    }
}
