package com.codefit.config;

import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * #183 review fix: an install that already had {@code review_history} rows before
 * {@code hint_usage_recorded} existed must have those rows become UNKNOWN when the column is added,
 * never reinterpreted as "known, hint-free" just because {@code hint_used} happens to default to 0.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class HintUsageRecordedMigrationTest {

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void existingReviewRowsBecomeUnknownNotKnownHintFreeWhenTheColumnIsAdded() throws SQLException {
        long legacyRowId;
        try (Connection connection = DatabaseConfig.getConnection(); Statement statement = connection.createStatement()) {
            // Simulate an install that predates this column entirely: drop it, then insert a row the
            // way the pre-#183 app always did - with no opinion on hint usage at all.
            statement.execute("ALTER TABLE review_history DROP COLUMN hint_usage_recorded");

            long deckId;
            try (PreparedStatement insertDeck = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES ('migration-fixture', 'fixture')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertDeck.executeUpdate();
                try (ResultSet keys = insertDeck.getGeneratedKeys()) {
                    keys.next();
                    deckId = keys.getLong(1);
                }
            }
            long flashcardId;
            try (PreparedStatement insertCard = connection.prepareStatement(
                    "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, review_count, due_date) "
                            + "VALUES (?, 'front', 'back', 'RECALL', 'x', 0, date('now'))",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertCard.setLong(1, deckId);
                insertCard.executeUpdate();
                try (ResultSet keys = insertCard.getGeneratedKeys()) {
                    keys.next();
                    flashcardId = keys.getLong(1);
                }
            }
            try (PreparedStatement insertReview = connection.prepareStatement(
                    "INSERT INTO review_history (flashcard_id, rating, previous_interval_days, new_interval_days, hint_used) "
                            + "VALUES (?, 'GOOD', 0, 1, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertReview.setLong(1, flashcardId);
                insertReview.executeUpdate();
                try (ResultSet keys = insertReview.getGeneratedKeys()) {
                    keys.next();
                    legacyRowId = keys.getLong(1);
                }
            }
        }

        // The exact idempotent startup path every real app launch (including an upgrade) runs.
        DatabaseConfig.initialize();

        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT hint_used, hint_usage_recorded FROM review_history WHERE id = ?")) {
            statement.setLong(1, legacyRowId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertEquals(true, resultSet.next());
                assertEquals(0, resultSet.getInt("hint_used"), "the pre-existing value is untouched");
                assertFalse(resultSet.getInt("hint_usage_recorded") == 1,
                        "a row that existed before this column did must become UNKNOWN (0), never reinterpreted "
                                + "as a known, recorded hint-free review just because hint_used defaults to 0");
            }
        }
    }

    @Test
    void aGenuinelyNewReviewIsRecordedAsKnown() throws SQLException {
        long legacyDeckId;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement insertDeck = connection.prepareStatement(
                     "INSERT INTO decks (name, description) VALUES ('migration-fixture-2', 'fixture')",
                     Statement.RETURN_GENERATED_KEYS)) {
            insertDeck.executeUpdate();
            try (ResultSet keys = insertDeck.getGeneratedKeys()) {
                keys.next();
                legacyDeckId = keys.getLong(1);
            }
        }
        long flashcardId;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement insertCard = connection.prepareStatement(
                     "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, review_count, due_date) "
                             + "VALUES (?, 'front', 'back', 'RECALL', 'x', 0, date('now'))",
                     Statement.RETURN_GENERATED_KEYS)) {
            insertCard.setLong(1, legacyDeckId);
            insertCard.executeUpdate();
            try (ResultSet keys = insertCard.getGeneratedKeys()) {
                keys.next();
                flashcardId = keys.getLong(1);
            }
        }

        com.codefit.model.ReviewHistory saved = new com.codefit.repository.ReviewHistoryRepository().save(
                new com.codefit.model.ReviewHistory(0, flashcardId, com.codefit.model.ReviewRating.GOOD, 0, 1,
                        java.time.LocalDateTime.now(), true, false, "EXACT", null, null, false, null, null));

        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT hint_usage_recorded FROM review_history WHERE id = ?")) {
            statement.setLong(1, saved.getId());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(1, resultSet.getInt("hint_usage_recorded"), "a genuinely new review is always recorded as known");
            }
        }
    }
}
