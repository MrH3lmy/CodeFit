package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CompletionOrigin;
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
 *
 * <p>Every operation has a {@link Connection}-scoped overload so the workbook importer (#159) can run
 * inside one shared transaction with everything else the import touches.
 */
public class ProblemAttemptRepository {

    public List<ProblemAttempt> findByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByProblemId(connection, problemId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem attempts", exception);
        }
    }

    public List<ProblemAttempt> findByProblemId(Connection connection, long problemId) throws SQLException {
        String sql = "SELECT * FROM problem_attempts WHERE problem_id = ? ORDER BY attempt_number";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    /**
     * Attempts whose {@code submitted_at} falls in {@code [startUtcInclusive, endUtcExclusive)}, both
     * given as the window's UTC bounds converted to a naive {@link LocalDateTime} (#183:
     * {@code submitted_at} is {@code CURRENT_TIMESTAMP}-sourced, i.e. {@code LEGACY_SQLITE_UTC} — exact
     * UTC despite carrying no offset — so the caller must pass UTC bounds here, never comparison-zone
     * bounds). {@code datetime()} on both sides normalizes the SQLite-default ' ' separator against
     * Java's 'T' separator before comparing, exactly as {@code ReviewHistoryRepository.findFiltered}
     * already does for the same reason.
     *
     * <p>Only origins whose timestamps are known to be period-attributable are returned. IMPORTED
     * is excluded because its timestamp is the workbook import time. UNKNOWN is also excluded:
     * before completion_origin existed, the database already contained both genuine attempts and
     * workbook-imported attempts, so an upgraded UNKNOWN row cannot safely be treated as either.
     * Callers must pair this query with {@link #hasUnknownSubmittedBetweenUtc} and mark aggregate
     * activity metrics UNAVAILABLE when ambiguous legacy evidence exists in the requested window.
     */
    public List<ProblemAttempt> findSubmittedBetweenUtc(LocalDateTime startUtcInclusive, LocalDateTime endUtcExclusive) {
        String sql = "SELECT * FROM problem_attempts WHERE datetime(submitted_at) >= datetime(?) "
                + "AND datetime(submitted_at) < datetime(?) "
                + "AND completion_origin IN ('FRESH_ATTEMPT', 'PREVIOUSLY_SOLVED') "
                + "ORDER BY problem_id, attempt_number";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, startUtcInclusive.toString());
            statement.setString(2, endUtcExclusive.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem attempts in window", exception);
        }
    }

    /**
     * Whether this window contains a pre-completion_origin attempt whose time provenance cannot be
     * recovered safely. UNKNOWN may be a genuine legacy workspace attempt or a pre-#183 workbook
     * import stamped with the import run's current time; the old schema did not persist enough
     * provenance to tell them apart. Presence of one makes exact period activity totals unknowable.
     */
    public boolean hasUnknownSubmittedBetweenUtc(LocalDateTime startUtcInclusive, LocalDateTime endUtcExclusive) {
        String sql = "SELECT 1 FROM problem_attempts WHERE datetime(submitted_at) >= datetime(?) "
                + "AND datetime(submitted_at) < datetime(?) AND completion_origin = 'UNKNOWN' LIMIT 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, startUtcInclusive.toString());
            statement.setString(2, endUtcExclusive.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect ambiguous legacy problem attempts in window", exception);
        }
    }

    /**
     * How many problems' FIRST successful (AC/ACX) attempt both falls in {@code
     * [startUtcInclusive, endUtcExclusive)} AND carries {@code completion_origin = FRESH_ATTEMPT}
     * (#183 review fix, round 4). {@code problem_attempts} is append-only — nothing in this
     * repository or anywhere else ever updates or deletes a row — so pinning "first" to {@code
     * MIN(attempt_number)} among a problem's AC/ACX attempts is immutable and tie-proof
     * (attempt_number is a unique, strictly increasing per-problem sequence, unlike
     * {@code submitted_at} which two attempts could in principle share): once computed for a given
     * problem, no later attempt at that same problem (another successful re-solve, a retried
     * import, anything) can ever change which attempt was first or when it happened, unlike {@code
     * problem_progress.completed_at} (which {@code ProblemSolvingWorkspaceService} overwrites on
     * every later successful finish).
     *
     * <p>The {@code completion_origin = 'FRESH_ATTEMPT'} filter is what keeps {@code
     * ProblemSolvingWorkspaceService#markPreviouslySolved} from ever counting here: that method
     * also records a {@code SUBMITTED}/{@code ACX} attempt (so it can still be the problem's
     * earliest AC/ACX row), but it is recorded explicitly as {@code PREVIOUSLY_SOLVED} - CodeFit has
     * no idea when the learner actually first solved it - so it is excluded even though it is the
     * earliest success on file. A genuine accept-after-failures (WA, WA, ACX through the real
     * workspace flow) is still {@code FRESH_ATTEMPT} and still counts. A problem whose earliest
     * success predates this column, or arrived only through import/legacy data, is {@code UNKNOWN}
     * and is excluded the same way — unavailable, never fabricated.
     */
    public int countFreshFirstCompletionsBetweenUtc(LocalDateTime startUtcInclusive, LocalDateTime endUtcExclusive) {
        String sql = "SELECT COUNT(*) FROM ("
                + "  SELECT pa.problem_id, pa.submitted_at AS first_success, pa.completion_origin AS first_origin "
                + "  FROM problem_attempts pa "
                + "  WHERE pa.submission_result IN ('AC', 'ACX') "
                + "    AND pa.attempt_number = ("
                + "      SELECT MIN(pa2.attempt_number) FROM problem_attempts pa2 "
                + "      WHERE pa2.problem_id = pa.problem_id AND pa2.submission_result IN ('AC', 'ACX')"
                + "    )"
                + ") first_successes "
                + "WHERE first_origin = 'FRESH_ATTEMPT' "
                + "  AND datetime(first_success) >= datetime(?) AND datetime(first_success) < datetime(?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, startUtcInclusive.toString());
            statement.setString(2, endUtcExclusive.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count fresh first completions in window", exception);
        }
    }

    /** Every attempt across every problem, for dashboard aggregation (#147) — one query rather than
     *  one round trip per problem, so aggregation stays responsive with the full imported roadmap. */
    public List<ProblemAttempt> findAll() {
        String sql = "SELECT * FROM problem_attempts ORDER BY problem_id, attempt_number";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load all problem attempts", exception);
        }
    }

    public int countByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return countByProblemId(connection, problemId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count problem attempts", exception);
        }
    }

    public int countByProblemId(Connection connection, long problemId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM problem_attempts WHERE problem_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    public ProblemAttempt save(ProblemAttempt attempt) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, attempt);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem attempt", exception);
        }
    }

    public ProblemAttempt save(Connection connection, ProblemAttempt attempt) throws SQLException {
        String sql = "INSERT INTO problem_attempts (problem_id, attempt_number, submission_result, "
                + "reading_time_seconds, thinking_time_seconds, coding_time_seconds, debugging_time_seconds, notes, "
                + "session_outcome, completion_origin) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        CompletionOrigin completionOrigin = attempt.completionOrigin() == null ? CompletionOrigin.UNKNOWN : attempt.completionOrigin();
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, attempt.problemId());
            statement.setInt(2, attempt.attemptNumber());
            statement.setString(3, attempt.submissionResult().name());
            setNullableInt(statement, 4, attempt.readingTimeSeconds());
            setNullableInt(statement, 5, attempt.thinkingTimeSeconds());
            setNullableInt(statement, 6, attempt.codingTimeSeconds());
            setNullableInt(statement, 7, attempt.debuggingTimeSeconds());
            statement.setString(8, attempt.notes());
            statement.setString(9, attempt.sessionOutcome() == null ? null : attempt.sessionOutcome().name());
            statement.setString(10, completionOrigin.name());
            statement.executeUpdate();
            long id = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new ProblemAttempt(id, attempt.problemId(), attempt.attemptNumber(), attempt.submissionResult(),
                    attempt.readingTimeSeconds(), attempt.thinkingTimeSeconds(), attempt.codingTimeSeconds(),
                    attempt.debuggingTimeSeconds(), attempt.submittedAt(), attempt.notes(), attempt.sessionOutcome(),
                    completionOrigin);
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
            String completionOrigin = resultSet.getString("completion_origin");
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
                    sessionOutcome == null ? null : SessionFinishOutcome.valueOf(sessionOutcome),
                    completionOrigin == null ? CompletionOrigin.UNKNOWN : CompletionOrigin.valueOf(completionOrigin)));
        }
        return attempts;
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
