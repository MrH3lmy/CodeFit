package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
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
        String sql = "INSERT INTO problem_progress (problem_id, state, perceived_difficulty, solved_with, "
                + "final_category, approach_notes, mistake_notes, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
        String sql = "UPDATE problem_progress SET state = ?, perceived_difficulty = ?, solved_with = ?, "
                + "final_category = ?, approach_notes = ?, mistake_notes = ?, completed_at = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, progress.getState().name());
            statement.setString(2, progress.getPerceivedDifficulty() == null ? null : progress.getPerceivedDifficulty().name());
            statement.setString(3, progress.getSolvedWith() == null ? null : progress.getSolvedWith().name());
            statement.setString(4, progress.getFinalCategory() == null ? null : progress.getFinalCategory().name());
            statement.setString(5, progress.getApproachNotes());
            statement.setString(6, progress.getMistakeNotes());
            statement.setString(7, progress.getCompletedAt() == null ? null : progress.getCompletedAt().toString());
            statement.setLong(8, progress.getProblemId());
            statement.executeUpdate();
        }
    }

    private void bindInsertFields(PreparedStatement statement, ProblemProgress progress) throws SQLException {
        statement.setLong(1, progress.getProblemId());
        statement.setString(2, progress.getState().name());
        statement.setString(3, progress.getPerceivedDifficulty() == null ? null : progress.getPerceivedDifficulty().name());
        statement.setString(4, progress.getSolvedWith() == null ? null : progress.getSolvedWith().name());
        statement.setString(5, progress.getFinalCategory() == null ? null : progress.getFinalCategory().name());
        statement.setString(6, progress.getApproachNotes());
        statement.setString(7, progress.getMistakeNotes());
        statement.setString(8, progress.getCompletedAt() == null ? null : progress.getCompletedAt().toString());
    }

    private ProblemProgress mapProgress(ResultSet resultSet) throws SQLException {
        String perceivedDifficulty = resultSet.getString("perceived_difficulty");
        String solvedWith = resultSet.getString("solved_with");
        String finalCategory = resultSet.getString("final_category");
        String completedAt = resultSet.getString("completed_at");
        return new ProblemProgress(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                ProblemState.valueOf(resultSet.getString("state")),
                perceivedDifficulty == null ? null : DifficultyLevel.valueOf(perceivedDifficulty),
                solvedWith == null ? null : SolvedWith.valueOf(solvedWith),
                finalCategory == null ? null : FinalCategory.valueOf(finalCategory),
                resultSet.getString("approach_notes"),
                resultSet.getString("mistake_notes"),
                completedAt == null ? null : LocalDateTime.parse(completedAt.replace(' ', 'T')),
                LocalDateTime.parse(resultSet.getString("updated_at").replace(' ', 'T'))
        );
    }
}
