package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.service.InterviewMockEvaluation;
import com.codefit.service.InterviewMockMode;
import com.codefit.service.InterviewMockPlan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists scored interview mocks in tables isolated from flashcard review, weekly assessments,
 * and problem-solving progress. Mock scores are diagnostic interview evidence only: saving one can
 * never change a flashcard interval or mark a coding problem solved.
 *
 * <p>The schema is ensured lazily on each public operation rather than relying on a one-shot
 * application-start migration. CodeFit's tests can switch the global SQLite URL to a fresh isolated
 * database after service construction, so ensuring against the currently-active database here keeps
 * the repository correct in both production and isolated tests.</p>
 */
public class InterviewMockRepository {

    public void save(InterviewMockEvaluation evaluation) {
        ensureSchema();
        try (Connection connection = DatabaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            try {
                enableForeignKeys(connection);
                long runId = insertRun(connection, evaluation);
                insertStageScores(connection, runId, evaluation.stageScores());
                insertDomainScores(connection, runId, evaluation.domainScores());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save interview mock evaluation", exception);
        }
    }

    public List<StoredRun> findRecentRuns(String profileId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ensureSchema();
        String sql = "SELECT id, run_id, profile_id, mode, overall_score_percent, completed_at, notes "
                + "FROM interview_mock_runs WHERE profile_id = ? ORDER BY completed_at DESC, id DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredRun> runs = new ArrayList<>();
                while (resultSet.next()) {
                    runs.add(new StoredRun(
                            resultSet.getLong("id"),
                            resultSet.getString("run_id"),
                            resultSet.getString("profile_id"),
                            InterviewMockMode.valueOf(resultSet.getString("mode")),
                            resultSet.getInt("overall_score_percent"),
                            parseDateTime(resultSet.getString("completed_at")),
                            resultSet.getString("notes")));
                }
                return runs;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load recent interview mock runs", exception);
        }
    }

    /** Latest scored mock evidence for one interview domain, newest first. */
    public List<StoredDomainScore> findRecentDomainScores(String profileId, String domainId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ensureSchema();
        String sql = "SELECT d.domain_id, d.score_percent, r.run_id, r.mode, r.completed_at "
                + "FROM interview_mock_domain_scores d "
                + "JOIN interview_mock_runs r ON r.id = d.mock_run_id "
                + "WHERE r.profile_id = ? AND d.domain_id = ? "
                + "ORDER BY r.completed_at DESC, r.id DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            statement.setString(2, domainId);
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredDomainScore> scores = new ArrayList<>();
                while (resultSet.next()) {
                    scores.add(new StoredDomainScore(
                            resultSet.getString("run_id"),
                            resultSet.getString("domain_id"),
                            resultSet.getInt("score_percent"),
                            InterviewMockMode.valueOf(resultSet.getString("mode")),
                            parseDateTime(resultSet.getString("completed_at"))));
                }
                return scores;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load interview mock domain scores", exception);
        }
    }

    private long insertRun(Connection connection, InterviewMockEvaluation evaluation) throws SQLException {
        String sql = "INSERT INTO interview_mock_runs (run_id, profile_id, mode, overall_score_percent, completed_at, notes) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, evaluation.runId());
            statement.setString(2, evaluation.profileId());
            statement.setString(3, evaluation.mode().name());
            statement.setInt(4, evaluation.overallScorePercent());
            statement.setString(5, evaluation.completedAt().toString());
            statement.setString(6, evaluation.notes());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for interview mock run.");
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertStageScores(Connection connection, long mockRunId,
                                   List<InterviewMockEvaluation.StageScore> stageScores) throws SQLException {
        String sql = "INSERT INTO interview_mock_stage_scores (mock_run_id, stage_id, stage_type, score_percent) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (InterviewMockEvaluation.StageScore stageScore : stageScores) {
                statement.setLong(1, mockRunId);
                statement.setString(2, stageScore.stageId());
                statement.setString(3, stageScore.stageType().name());
                statement.setInt(4, stageScore.scorePercent());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertDomainScores(Connection connection, long mockRunId,
                                    List<InterviewMockEvaluation.DomainScore> domainScores) throws SQLException {
        String sql = "INSERT INTO interview_mock_domain_scores (mock_run_id, domain_id, score_percent) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (InterviewMockEvaluation.DomainScore domainScore : domainScores) {
                statement.setLong(1, mockRunId);
                statement.setString(2, domainScore.domainId());
                statement.setInt(3, domainScore.scorePercent());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void ensureSchema() {
        try (Connection connection = DatabaseConfig.getConnection(); Statement statement = connection.createStatement()) {
            enableForeignKeys(connection);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS interview_mock_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id TEXT NOT NULL UNIQUE,
                        profile_id TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        overall_score_percent INTEGER NOT NULL CHECK (overall_score_percent BETWEEN 0 AND 100),
                        completed_at TEXT NOT NULL,
                        notes TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS interview_mock_stage_scores (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        mock_run_id INTEGER NOT NULL,
                        stage_id TEXT NOT NULL,
                        stage_type TEXT NOT NULL,
                        score_percent INTEGER NOT NULL CHECK (score_percent BETWEEN 0 AND 100),
                        FOREIGN KEY(mock_run_id) REFERENCES interview_mock_runs(id) ON DELETE CASCADE,
                        UNIQUE(mock_run_id, stage_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS interview_mock_domain_scores (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        mock_run_id INTEGER NOT NULL,
                        domain_id TEXT NOT NULL,
                        score_percent INTEGER NOT NULL CHECK (score_percent BETWEEN 0 AND 100),
                        FOREIGN KEY(mock_run_id) REFERENCES interview_mock_runs(id) ON DELETE CASCADE,
                        UNIQUE(mock_run_id, domain_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_interview_mock_runs_profile_completed "
                    + "ON interview_mock_runs(profile_id, completed_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_interview_mock_domain_lookup "
                    + "ON interview_mock_domain_scores(domain_id, mock_run_id)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize interview mock schema", exception);
        }
    }

    private static void enableForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        return LocalDateTime.parse(value.replace(' ', 'T'));
    }

    public record StoredRun(long id, String runId, String profileId, InterviewMockMode mode,
                            int overallScorePercent, LocalDateTime completedAt, String notes) {
    }

    public record StoredDomainScore(String runId, String domainId, int scorePercent, InterviewMockMode mode,
                                    LocalDateTime completedAt) {
    }
}
