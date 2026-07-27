package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ComplexityClass;
import com.codefit.model.FinalCategory;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.SolvedWith;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Persists each {@link Problem}'s single {@link ProblemProgress} row. {@code problem_id} is unique
 * in {@code problem_progress}, so {@link #save(ProblemProgress)} and {@link #update(ProblemProgress)}
 * are kept separate rather than offered as one "upsert" call: callers (see
 * {@code ProblemProgressService}) decide which one applies by checking {@link #findByProblemId(long)}
 * first, which keeps the "exactly one progress record per problem" invariant explicit at the call
 * site instead of hidden behind a single ambiguous method.
 *
 * <p>Every operation has a {@link Connection}-scoped overload so the workbook importer (#143) can
 * run every row inside one shared transaction.
 */
public class ProblemProgressRepository {

    public Optional<ProblemProgress> findByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByProblemId(connection, problemId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem progress", exception);
        }
    }

    public Optional<ProblemProgress> findByProblemId(Connection connection, long problemId) throws SQLException {
        String sql = "SELECT * FROM problem_progress WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProgress(resultSet)) : Optional.empty();
            }
        }
    }

    public ProblemProgress save(ProblemProgress progress) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, progress);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem progress", exception);
        }
    }

    public ProblemProgress save(Connection connection, ProblemProgress progress) throws SQLException {
        String sql = "INSERT INTO problem_progress (problem_id, state, perceived_difficulty_rating, solved_with, "
                + "final_category, approach_notes, mistake_notes, important_observation, time_complexity, "
                + "space_complexity, lesson_learned, actual_topic, editorial_understood, other_solutions_reviewed, "
                + "simpler_implementation_considered, better_complexity_considered, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsertFields(statement, progress);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    progress.setId(keys.getLong(1));
                }
            }
        }
        return findByProblemId(connection, progress.getProblemId()).orElseThrow();
    }

    public void update(ProblemProgress progress) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            update(connection, progress);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem progress", exception);
        }
    }

    public void update(Connection connection, ProblemProgress progress) throws SQLException {
        String sql = "UPDATE problem_progress SET state = ?, perceived_difficulty_rating = ?, solved_with = ?, "
                + "final_category = ?, approach_notes = ?, mistake_notes = ?, important_observation = ?, "
                + "time_complexity = ?, space_complexity = ?, lesson_learned = ?, actual_topic = ?, "
                + "editorial_understood = ?, other_solutions_reviewed = ?, simpler_implementation_considered = ?, "
                + "better_complexity_considered = ?, completed_at = ?, updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, progress.getState().name());
            setNullableInt(statement, 2, progress.getPerceivedDifficultyRating());
            statement.setString(3, progress.getSolvedWith() == null ? null : progress.getSolvedWith().name());
            statement.setString(4, progress.getFinalCategory() == null ? null : progress.getFinalCategory().name());
            statement.setString(5, progress.getApproachNotes());
            statement.setString(6, progress.getMistakeNotes());
            statement.setString(7, progress.getImportantObservation());
            statement.setString(8, progress.getTimeComplexity() == null ? null : progress.getTimeComplexity().name());
            statement.setString(9, progress.getSpaceComplexity() == null ? null : progress.getSpaceComplexity().name());
            statement.setString(10, progress.getLessonLearned());
            statement.setString(11, progress.getActualTopic());
            statement.setInt(12, progress.isEditorialUnderstood() ? 1 : 0);
            statement.setInt(13, progress.isOtherSolutionsReviewed() ? 1 : 0);
            statement.setInt(14, progress.isSimplerImplementationConsidered() ? 1 : 0);
            statement.setInt(15, progress.isBetterComplexityConsidered() ? 1 : 0);
            statement.setString(16, progress.getCompletedAt() == null ? null : progress.getCompletedAt().toString());
            statement.setLong(17, progress.getProblemId());
            statement.executeUpdate();
        }
    }

    private void bindInsertFields(PreparedStatement statement, ProblemProgress progress) throws SQLException {
        statement.setLong(1, progress.getProblemId());
        statement.setString(2, progress.getState().name());
        setNullableInt(statement, 3, progress.getPerceivedDifficultyRating());
        statement.setString(4, progress.getSolvedWith() == null ? null : progress.getSolvedWith().name());
        statement.setString(5, progress.getFinalCategory() == null ? null : progress.getFinalCategory().name());
        statement.setString(6, progress.getApproachNotes());
        statement.setString(7, progress.getMistakeNotes());
        statement.setString(8, progress.getImportantObservation());
        statement.setString(9, progress.getTimeComplexity() == null ? null : progress.getTimeComplexity().name());
        statement.setString(10, progress.getSpaceComplexity() == null ? null : progress.getSpaceComplexity().name());
        statement.setString(11, progress.getLessonLearned());
        statement.setString(12, progress.getActualTopic());
        statement.setInt(13, progress.isEditorialUnderstood() ? 1 : 0);
        statement.setInt(14, progress.isOtherSolutionsReviewed() ? 1 : 0);
        statement.setInt(15, progress.isSimplerImplementationConsidered() ? 1 : 0);
        statement.setInt(16, progress.isBetterComplexityConsidered() ? 1 : 0);
        statement.setString(17, progress.getCompletedAt() == null ? null : progress.getCompletedAt().toString());
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private ProblemProgress mapProgress(ResultSet resultSet) throws SQLException {
        String solvedWith = resultSet.getString("solved_with");
        String finalCategory = resultSet.getString("final_category");
        String timeComplexity = resultSet.getString("time_complexity");
        String spaceComplexity = resultSet.getString("space_complexity");
        String completedAt = resultSet.getString("completed_at");
        return new ProblemProgress(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                ProblemState.valueOf(resultSet.getString("state")),
                nullableInt(resultSet, "perceived_difficulty_rating"),
                solvedWith == null ? null : SolvedWith.valueOf(solvedWith),
                finalCategory == null ? null : FinalCategory.valueOf(finalCategory),
                resultSet.getString("approach_notes"),
                resultSet.getString("mistake_notes"),
                resultSet.getString("important_observation"),
                timeComplexity == null ? null : ComplexityClass.valueOf(timeComplexity),
                spaceComplexity == null ? null : ComplexityClass.valueOf(spaceComplexity),
                resultSet.getString("lesson_learned"),
                resultSet.getString("actual_topic"),
                resultSet.getInt("editorial_understood") == 1,
                resultSet.getInt("other_solutions_reviewed") == 1,
                resultSet.getInt("simpler_implementation_considered") == 1,
                resultSet.getInt("better_complexity_considered") == 1,
                completedAt == null ? null : LocalDateTime.parse(completedAt.replace(' ', 'T')),
                LocalDateTime.parse(resultSet.getString("updated_at").replace(' ', 'T'))
        );
    }

    private Integer nullableInt(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
