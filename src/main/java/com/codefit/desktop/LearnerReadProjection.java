package com.codefit.desktop;

import java.sql.*;
import java.time.*;

/**
 * Recorded facts only. All queries share a caller-owned snapshot; no reconciliation or defaults.
 */
final class LearnerReadProjection {
    record Overview(
            String profileId,
            String sessionId,
            Instant capturedAt,
            String displayName,
            Long xp,
            Long level,
            long reviewCount,
            long dueScheduledCards,
            String reviewDay) {}

    static Overview read(Connection c, String profileId, String sessionId, Instant capturedAt)
            throws SQLException {
        String name = null;
        // Never load avatar, bio, peer identity keys, assessment text or evaluation inputs.
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT CASE WHEN length(display_name) BETWEEN 1 AND 120 THEN"
                                    + " display_name END FROM social_profile WHERE id=1 LIMIT 1")) {
            if (r.next()) {
                String value = r.getString(1);
                if (ApplicationProtocol.validText(value, 120) && !value.isBlank()) name = value;
            }
        }
        Long xp = null, level = null;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT xp,level,typeof(xp),typeof(level) FROM user_progress WHERE"
                                    + " id=1 LIMIT 1")) {
            if (r.next() && "integer".equals(r.getString(3)) && "integer".equals(r.getString(4))) {
                long recordedXp = r.getLong(1), recordedLevel = r.getLong(2);
                if (recordedXp >= 0
                        && recordedXp <= 9007199254740991L
                        && recordedLevel >= 1
                        && recordedLevel <= 9007199254740991L) {
                    xp = recordedXp;
                    level = recordedLevel;
                }
            }
        }
        long reviews;
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT count(*) FROM review_history")) {
            if (!r.next()) throw new SQLException("READ_FAILED");
            reviews = r.getLong(1);
        }
        // Same scheduled-card states/date comparison as FlashcardRepository.findDueCards().
        // Uses capture day rather than advancing time on a frozen historical snapshot.
        String day = capturedAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
        long due;
        try (var s =
                c.prepareStatement(
                        "SELECT count(*) FROM flashcards WHERE due_date <= ? AND card_state IN"
                            + " ('REVIEW','RELEARNING','GRADUATED','LEECH')")) {
            s.setString(1, day);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("READ_FAILED");
                due = r.getLong(1);
            }
        }
        return new Overview(profileId, sessionId, capturedAt, name, xp, level, reviews, due, day);
    }
}
