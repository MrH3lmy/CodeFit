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
 *
 * <p>Every operation has a {@link Connection}-scoped overload alongside the convenience method that
 * opens its own connection. The workbook importer (#143) uses the {@code Connection}-scoped
 * overloads so every row it processes runs inside one shared transaction; every other caller keeps
 * using the plain overloads unchanged.
 */
public class ProblemRepository {

    public List<Problem> findAll() {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findAll(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problems", exception);
        }
    }

    public List<Problem> findAll(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM problems ORDER BY title");
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        }
    }

    public Optional<Problem> findById(long id) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findById(connection, id);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem", exception);
        }
    }

    public Optional<Problem> findById(Connection connection, long id) throws SQLException {
        String sql = "SELECT * FROM problems WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProblem(resultSet)) : Optional.empty();
            }
        }
    }

    /** The natural key used to keep repeated workbook imports from creating duplicate problems. */
    public Optional<Problem> findByPlatformAndExternalCode(String platform, String externalCode) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByPlatformAndExternalCode(connection, platform, externalCode);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem by natural key", exception);
        }
    }

    public Optional<Problem> findByPlatformAndExternalCode(Connection connection, String platform, String externalCode) throws SQLException {
        String sql = "SELECT * FROM problems WHERE platform = ? AND external_code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, platform);
            statement.setString(2, externalCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProblem(resultSet)) : Optional.empty();
            }
        }
    }

    /** Every problem whose external code matches, regardless of platform (used by Topics-sheet lookups). */
    public List<Problem> findAllByExternalCode(Connection connection, String externalCode) throws SQLException {
        String sql = "SELECT * FROM problems WHERE external_code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, externalCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
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
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, problem);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem", exception);
        }
    }

    public Problem save(Connection connection, Problem problem) throws SQLException {
        String sql = "INSERT INTO problems (external_code, platform, title, url, topic, quality_rating, learning_resources) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
        }
        return findById(connection, problem.getId()).orElseThrow();
    }

    /** Updates the catalog fields of an existing problem; identity (platform, externalCode) never changes. */
    public void update(Problem problem) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            update(connection, problem);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem", exception);
        }
    }

    public void update(Connection connection, Problem problem) throws SQLException {
        String sql = "UPDATE problems SET title = ?, url = ?, topic = ?, quality_rating = ?, learning_resources = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
