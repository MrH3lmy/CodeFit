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
        String sql = "INSERT INTO review_history (flashcard_id, rating, previous_interval_days, new_interval_days) VALUES (?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, history.getFlashcardId());
            statement.setString(2, history.getRating().name());
            statement.setInt(3, history.getPreviousIntervalDays());
            statement.setInt(4, history.getNewIntervalDays());
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

    public List<ReviewHistory> findRecent(int limit) {
        String sql = "SELECT * FROM review_history ORDER BY reviewed_at DESC LIMIT ?";
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
        String sql = "SELECT COUNT(*) FROM review_history WHERE date(reviewed_at) = date('now')";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count today's reviews", exception);
        }
    }

    private ReviewHistory mapReviewHistory(ResultSet resultSet) throws SQLException {
        return new ReviewHistory(
                resultSet.getLong("id"),
                resultSet.getLong("flashcard_id"),
                ReviewRating.valueOf(resultSet.getString("rating")),
                resultSet.getInt("previous_interval_days"),
                resultSet.getInt("new_interval_days"),
                LocalDateTime.parse(resultSet.getString("reviewed_at").replace(' ', 'T'))
        );
    }
}
