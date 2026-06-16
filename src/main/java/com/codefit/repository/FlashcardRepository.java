package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FlashcardRepository {
    public List<Flashcard> findAll() {
        return query("SELECT * FROM flashcards ORDER BY created_at DESC");
    }

    public List<Flashcard> findByDeckId(long deckId) {
        String sql = "SELECT * FROM flashcards WHERE deck_id = ? ORDER BY created_at DESC";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load cards", exception);
        }
    }

    public List<Flashcard> findDueCards() {
        return query("SELECT * FROM flashcards WHERE due_date <= date('now') ORDER BY due_date, created_at");
    }

    public Optional<Flashcard> findById(long id) {
        String sql = "SELECT * FROM flashcards WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapFlashcard(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load card", exception);
        }
    }

    public Flashcard save(Flashcard flashcard) {
        String sql = "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, validation_mode, simulated_output, time_limit_seconds, due_date, interval_days, ease_factor, review_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, flashcard.getDeckId());
            statement.setString(2, flashcard.getFront());
            statement.setString(3, flashcard.getBack());
            statement.setString(4, flashcard.getCardType().name());
            statement.setString(5, flashcard.getAcceptedAnswers());
            statement.setString(6, flashcard.getValidationMode().name());
            statement.setString(7, flashcard.getSimulatedOutput());
            if (flashcard.getTimeLimitSeconds() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, flashcard.getTimeLimitSeconds());
            }
            statement.setString(9, flashcard.getDueDate().toString());
            statement.setInt(10, flashcard.getIntervalDays());
            statement.setDouble(11, flashcard.getEaseFactor());
            statement.setInt(12, flashcard.getReviewCount());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    flashcard.setId(keys.getLong(1));
                }
            }
            return flashcard;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save card", exception);
        }
    }

    public void updateSchedule(Flashcard flashcard) {
        String sql = "UPDATE flashcards SET due_date = ?, interval_days = ?, ease_factor = ?, review_count = ? WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flashcard.getDueDate().toString());
            statement.setInt(2, flashcard.getIntervalDays());
            statement.setDouble(3, flashcard.getEaseFactor());
            statement.setInt(4, flashcard.getReviewCount());
            statement.setLong(5, flashcard.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update card schedule", exception);
        }
    }

    public int countAll() {
        return count("SELECT COUNT(*) FROM flashcards");
    }

    public int countDue() {
        return count("SELECT COUNT(*) FROM flashcards WHERE due_date <= date('now')");
    }

    private List<Flashcard> query(String sql) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load cards", exception);
        }
    }

    private int count(String sql) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count cards", exception);
        }
    }

    private List<Flashcard> mapAll(ResultSet resultSet) throws SQLException {
        List<Flashcard> cards = new ArrayList<>();
        while (resultSet.next()) {
            cards.add(mapFlashcard(resultSet));
        }
        return cards;
    }

    private Flashcard mapFlashcard(ResultSet resultSet) throws SQLException {
        return new Flashcard(
                resultSet.getLong("id"),
                resultSet.getLong("deck_id"),
                resultSet.getString("front"),
                resultSet.getString("back"),
                enumValue(CardType.class, resultSet.getString("card_type"), CardType.RECALL),
                resultSet.getString("accepted_answers"),
                enumValue(ValidationMode.class, resultSet.getString("validation_mode"), ValidationMode.CASE_INSENSITIVE),
                resultSet.getString("simulated_output"),
                LocalDate.parse(resultSet.getString("due_date")),
                resultSet.getInt("interval_days"),
                resultSet.getDouble("ease_factor"),
                resultSet.getInt("review_count"),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T')),
                nullableInteger(resultSet, "time_limit_seconds")
        );
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumClass, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
