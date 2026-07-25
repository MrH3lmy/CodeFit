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

    /**
     * Deck, skill, and card type live on the flashcard, not the review, so this joins to
     * flashcards to apply those filters. Boss-battle attempts are excluded for consistency with
     * every other read path in this repository. Callers needing "no filter" should pass
     * {@link ReviewHistoryFilter#all()} rather than leaving fields null ad hoc.
     *
     * <p>{@code reviewed_at} is populated from SQLite's {@code CURRENT_TIMESTAMP} default (space
     * between date and time), while {@code filter.start()}/{@code end()} are formatted via
     * {@link LocalDateTime#toString()} ('T' separator); comparing those TEXT values directly would
     * silently exclude every row (' ' sorts before 'T'). Wrapping both sides in SQLite's
     * {@code datetime()} normalizes the separator before comparing.
     */
    public List<ReviewHistory> findFiltered(ReviewHistoryFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT rh.* FROM review_history rh JOIN flashcards f ON f.id = rh.flashcard_id WHERE rh.boss_battle = 0");
        List<Object> params = new ArrayList<>();
        if (filter.start() != null) {
            sql.append(" AND datetime(rh.reviewed_at) >= datetime(?)");
            params.add(filter.start().toString());
        }
        if (filter.end() != null) {
            sql.append(" AND datetime(rh.reviewed_at) <= datetime(?)");
            params.add(filter.end().toString());
        }
        if (filter.deckId() != null) {
            sql.append(" AND f.deck_id = ?");
            params.add(filter.deckId());
        }
        if (filter.skillCategory() != null && !filter.skillCategory().isBlank()) {
            sql.append(" AND f.skill_category = ?");
            params.add(filter.skillCategory());
        }
        if (filter.cardType() != null) {
            sql.append(" AND f.card_type = ?");
            params.add(filter.cardType().name());
        }
        sql.append(" ORDER BY rh.reviewed_at DESC, rh.id DESC");

        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ReviewHistory> history = new ArrayList<>();
                while (resultSet.next()) {
                    history.add(mapReviewHistory(resultSet));
                }
                return history;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load filtered review history", exception);
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
