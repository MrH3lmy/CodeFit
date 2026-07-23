package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ReviewHistoryRepository {
    public ReviewHistory save(ReviewHistory history) {
        String sql = "INSERT INTO review_history (flashcard_id, rating, previous_interval_days, new_interval_days, submitted_in_time, boss_battle, validation_result, submitted_answer, response_time_ms, hint_used, session_id, confidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, history.getFlashcardId());
            statement.setString(2, history.getRating().name());
            statement.setInt(3, history.getPreviousIntervalDays());
            statement.setInt(4, history.getNewIntervalDays());
            statement.setInt(5, history.isSubmittedInTime() ? 1 : 0);
            statement.setInt(6, history.isBossBattle() ? 1 : 0);
            statement.setString(7, history.getValidationResult());
            statement.setString(8, history.getSubmittedAnswer());
            if (history.getResponseTimeMs() == null) {
                statement.setNull(9, java.sql.Types.INTEGER);
            } else {
                statement.setInt(9, history.getResponseTimeMs());
            }
            statement.setInt(10, history.isHintUsed() ? 1 : 0);
            statement.setString(11, history.getSessionId());
            statement.setString(12, history.getConfidence());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    history.setId(keys.getLong(1));
                }
            }
            return history;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save review history", exception);
        }
    }

    public List<ReviewHistory> findRecentForFlashcard(long flashcardId, int limit) {
        String sql = "SELECT * FROM review_history WHERE flashcard_id = ? AND boss_battle = 0 ORDER BY reviewed_at DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, flashcardId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ReviewHistory> history = new ArrayList<>();
                while (resultSet.next()) {
                    history.add(mapReviewHistory(resultSet));
                }
                return history;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load review history for card", exception);
        }
    }

    public List<ReviewHistory> findRecent(int limit) {
        String sql = "SELECT * FROM review_history WHERE boss_battle = 0 ORDER BY reviewed_at DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ReviewHistory> history = new ArrayList<>();
                while (resultSet.next()) {
                    history.add(mapReviewHistory(resultSet));
                }
                return history;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load review history", exception);
        }
    }

    public int countReviewedToday() {
        String sql = "SELECT COUNT(*) FROM review_history WHERE boss_battle = 0 AND date(reviewed_at) = date('now')";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count today's reviews", exception);
        }
    }

    public List<ReviewHistory> findRecentBossBattles(int limit) {
        String sql = "SELECT * FROM review_history WHERE boss_battle = 1 ORDER BY reviewed_at DESC LIMIT ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ReviewHistory> history = new ArrayList<>();
                while (resultSet.next()) {
                    history.add(mapReviewHistory(resultSet));
                }
                return history;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load boss battle history", exception);
        }
    }

    public boolean hasBossBattleSince(java.time.LocalDate since) {
        String sql = "SELECT 1 FROM review_history WHERE boss_battle = 1 AND date(reviewed_at) >= date(?) LIMIT 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, since.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to check boss battle availability", exception);
        }
    }

    private ReviewHistory mapReviewHistory(ResultSet resultSet) throws SQLException {
        Integer responseTimeMs = resultSet.getInt("response_time_ms");
        if (resultSet.wasNull()) {
            responseTimeMs = null;
        }
        return new ReviewHistory(
                resultSet.getLong("id"),
                resultSet.getLong("flashcard_id"),
                ReviewRating.valueOf(resultSet.getString("rating")),
                resultSet.getInt("previous_interval_days"),
                resultSet.getInt("new_interval_days"),
                LocalDateTime.parse(resultSet.getString("reviewed_at").replace(' ', 'T')),
                resultSet.getInt("submitted_in_time") == 1,
                resultSet.getInt("boss_battle") == 1,
                resultSet.getString("validation_result"),
                resultSet.getString("submitted_answer"),
                responseTimeMs,
                resultSet.getInt("hint_used") == 1,
                resultSet.getString("session_id"),
                resultSet.getString("confidence")
        );
    }
}
