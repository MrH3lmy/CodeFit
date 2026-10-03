package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SocialProfileCard;
import com.codefit.peer.sync.PeerSocialProfileCard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Optional;

/** The validated peer inbox for {@code SOCIAL_PROFILE_CARD} (#184): one row per author, latest revision only. */
public class PeerSocialProfileCardRepository {

    public void upsert(Connection connection, IdentityId author, ObjectId objectId, long epoch, long revision,
                        SocialProfileCard body, Instant receivedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO peer_social_profile_cards (author_identity_id, object_id, epoch, revision, display_name, "
                        + "bio, comparison_zone_id, week_start, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(author_identity_id) DO UPDATE SET "
                        + "object_id = excluded.object_id, epoch = excluded.epoch, revision = excluded.revision, "
                        + "display_name = excluded.display_name, bio = excluded.bio, "
                        + "comparison_zone_id = excluded.comparison_zone_id, week_start = excluded.week_start, "
                        + "received_at = excluded.received_at")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            statement.setLong(3, epoch);
            statement.setLong(4, revision);
            statement.setString(5, body.displayName());
            statement.setString(6, body.bio());
            statement.setString(7, body.comparisonZoneId());
            statement.setInt(8, body.weekStart().getValue());
            statement.setString(9, receivedAt.toString());
            statement.executeUpdate();
        }
    }

    /** Deletes the cached copy for this author, if any - a tombstone's cache-deletion effect. */
    public void delete(Connection connection, IdentityId author, ObjectId objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM peer_social_profile_cards WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            statement.executeUpdate();
        }
    }

    public Optional<PeerSocialProfileCard> findByAuthor(IdentityId author) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM peer_social_profile_cards WHERE author_identity_id = ?")) {
            statement.setBytes(1, author.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                SocialProfileCard body = new SocialProfileCard(
                        resultSet.getString("display_name"), resultSet.getString("bio"),
                        resultSet.getString("comparison_zone_id"), DayOfWeek.of(resultSet.getInt("week_start")));
                return Optional.of(new PeerSocialProfileCard(author, new ObjectId(resultSet.getBytes("object_id")),
                        resultSet.getLong("epoch"), resultSet.getLong("revision"), body,
                        Instant.parse(resultSet.getString("received_at"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load peer social profile card", exception);
        }
    }
}
