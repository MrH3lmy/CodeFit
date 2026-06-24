package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.UserProgress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public class UserProgressRepository {
    public UserProgress getProgress() {
        String sql = "SELECT id, xp, level, streak_days, last_review_date, total_reviews, missed_day_count, streak_freeze_count, recovery_quest_active FROM user_progress WHERE id = 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                String lastReview = resultSet.getString("last_review_date");
                return new UserProgress(
                        resultSet.getLong("id"),
                        resultSet.getInt("xp"),
                        resultSet.getInt("level"),
                        resultSet.getInt("streak_days"),
                        lastReview == null ? null : LocalDate.parse(lastReview),
                        resultSet.getInt("total_reviews"),
                        resultSet.getInt("missed_day_count"),
                        resultSet.getInt("streak_freeze_count"),
                        resultSet.getInt("recovery_quest_active") == 1
                );
            }
            return new UserProgress(1, 0, 1, 0, null, 0);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load user progress", exception);
        }
    }

    public void save(UserProgress progress) {
        String sql = """
                UPDATE user_progress
                SET xp = ?, level = ?, streak_days = ?, last_review_date = ?, total_reviews = ?, missed_day_count = ?, streak_freeze_count = ?, recovery_quest_active = ?
                WHERE id = 1
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, progress.getXp());
            statement.setInt(2, progress.getLevel());
            statement.setInt(3, progress.getStreakDays());
            if (progress.getLastReviewDate() == null) {
                statement.setString(4, null);
            } else {
                statement.setString(4, progress.getLastReviewDate().toString());
            }
            statement.setInt(5, progress.getTotalReviews());
            statement.setInt(6, progress.getMissedDayCount());
            statement.setInt(7, progress.getStreakFreezeCount());
            statement.setInt(8, progress.isRecoveryQuestActive() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save user progress", exception);
        }
    }
}
