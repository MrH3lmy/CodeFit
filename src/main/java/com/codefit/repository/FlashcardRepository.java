package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CardState;
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
        return query("SELECT * FROM flashcards WHERE due_date <= date('now') "
                + "AND card_state IN ('REVIEW', 'RELEARNING') ORDER BY due_date, created_at");
    }

    public List<Flashcard> findNewCards() {
        return query("SELECT * FROM flashcards WHERE card_state = 'NEW' ORDER BY deck_id, created_at");
    }

    public int countIntroducedToday() {
        return count("SELECT COUNT(*) FROM flashcards WHERE introduced_at IS NOT NULL AND date(introduced_at) = date('now')");
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

    public boolean existsByDeckIdAndFront(long deckId, String front) {
        String sql = "SELECT 1 FROM flashcards WHERE deck_id = ? AND lower(front) = lower(?) LIMIT 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deckId);
            statement.setString(2, front == null ? "" : front.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to check for duplicate card", exception);
        }
    }

    public Flashcard save(Flashcard flashcard) {
        String sql = "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, validation_mode, simulated_output, hint, skill_category, time_limit_seconds, due_date, interval_days, ease_factor, review_count, card_state, introduced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, flashcard.getDeckId());
            statement.setString(2, flashcard.getFront());
            statement.setString(3, flashcard.getBack());
            statement.setString(4, flashcard.getCardType().name());
            statement.setString(5, flashcard.getAcceptedAnswers());
            statement.setString(6, flashcard.getValidationMode().name());
            statement.setString(7, flashcard.getSimulatedOutput());
            statement.setString(8, flashcard.getHint());
            statement.setString(9, flashcard.getSkillCategory());
            if (flashcard.getTimeLimitSeconds() == null) {
                statement.setNull(10, java.sql.Types.INTEGER);
            } else {
                statement.setInt(10, flashcard.getTimeLimitSeconds());
            }
            statement.setString(11, flashcard.getDueDate().toString());
            statement.setInt(12, flashcard.getIntervalDays());
            statement.setDouble(13, flashcard.getEaseFactor());
            statement.setInt(14, flashcard.getReviewCount());
            statement.setString(15, flashcard.getCardState().name());
            statement.setString(16, flashcard.getIntroducedAt() == null ? null : flashcard.getIntroducedAt().toString());
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

    /** Updates the editable content fields for an existing card; scheduling state (due date,
     *  interval, ease, review count, card state) is left untouched. */
    public void updateContent(Flashcard flashcard) {
        String sql = "UPDATE flashcards SET front = ?, back = ?, card_type = ?, accepted_answers = ?, "
                + "validation_mode = ?, simulated_output = ?, hint = ?, skill_category = ?, time_limit_seconds = ? "
                + "WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flashcard.getFront());
            statement.setString(2, flashcard.getBack());
            statement.setString(3, flashcard.getCardType().name());
            statement.setString(4, flashcard.getAcceptedAnswers());
            statement.setString(5, flashcard.getValidationMode().name());
            statement.setString(6, flashcard.getSimulatedOutput());
            statement.setString(7, flashcard.getHint());
            statement.setString(8, flashcard.getSkillCategory());
            if (flashcard.getTimeLimitSeconds() == null) {
                statement.setNull(9, java.sql.Types.INTEGER);
            } else {
                statement.setInt(9, flashcard.getTimeLimitSeconds());
            }
            statement.setLong(10, flashcard.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update card", exception);
        }
    }

    public void updateSchedule(Flashcard flashcard) {
        String sql = "UPDATE flashcards SET due_date = ?, interval_days = ?, ease_factor = ?, review_count = ?, "
                + "card_state = ?, introduced_at = ? WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flashcard.getDueDate().toString());
            statement.setInt(2, flashcard.getIntervalDays());
            statement.setDouble(3, flashcard.getEaseFactor());
            statement.setInt(4, flashcard.getReviewCount());
            statement.setString(5, flashcard.getCardState().name());
            statement.setString(6, flashcard.getIntroducedAt() == null ? null : flashcard.getIntroducedAt().toString());
            statement.setLong(7, flashcard.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update card schedule", exception);
        }
    }

    public int countAll() {
        return count("SELECT COUNT(*) FROM flashcards");
    }

    public int countDue() {
        return count("SELECT COUNT(*) FROM flashcards WHERE due_date <= date('now') "
                + "AND card_state IN ('REVIEW', 'RELEARNING')");
    }

    public int countNew() {
        return count("SELECT COUNT(*) FROM flashcards WHERE card_state = 'NEW'");
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
        Flashcard flashcard = new Flashcard(
                resultSet.getLong("id"),
                resultSet.getLong("deck_id"),
                resultSet.getString("front"),
                resultSet.getString("back"),
                enumValue(CardType.class, resultSet.getString("card_type"), CardType.RECALL),
                resultSet.getString("accepted_answers"),
                enumValue(ValidationMode.class, resultSet.getString("validation_mode"), ValidationMode.CASE_INSENSITIVE),
                resultSet.getString("simulated_output"),
                resultSet.getString("hint"),
                resultSet.getString("skill_category"),
                LocalDate.parse(resultSet.getString("due_date")),
                resultSet.getInt("interval_days"),
                resultSet.getDouble("ease_factor"),
                resultSet.getInt("review_count"),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T')),
                nullableInteger(resultSet, "time_limit_seconds")
        );
        flashcard.setCardState(enumValue(CardState.class, resultSet.getString("card_state"), CardState.NEW));
        flashcard.setIntroducedAt(nullableDateTime(resultSet, "introduced_at"));
        return flashcard;
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime nullableDateTime(ResultSet resultSet, String columnName) throws SQLException {
        String value = resultSet.getString(columnName);
        return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T'));
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
