package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.SocialProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Optional;

/** Persistence for the learner's own singleton {@link SocialProfile} row. */
public class SocialProfileRepository {

    public Optional<SocialProfile> find() {
        String sql = "SELECT display_name, bio, comparison_zone_id, week_start, avatar_bytes, avatar_mime_type, "
                + "profile_revision, updated_at FROM social_profile WHERE id = 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the social profile", exception);
        }
    }

    public void save(SocialProfile profile, Instant updatedAt) {
        String sql = """
                INSERT INTO social_profile (
                    id, display_name, bio, comparison_zone_id, week_start, avatar_bytes, avatar_mime_type,
                    profile_revision, updated_at
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    display_name = excluded.display_name,
                    bio = excluded.bio,
                    comparison_zone_id = excluded.comparison_zone_id,
                    week_start = excluded.week_start,
                    avatar_bytes = excluded.avatar_bytes,
                    avatar_mime_type = excluded.avatar_mime_type,
                    profile_revision = excluded.profile_revision,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.displayName());
            statement.setString(2, profile.bio());
            statement.setString(3, profile.comparisonZoneId());
            statement.setInt(4, profile.weekStart().getValue());
            statement.setBytes(5, profile.avatarBytes());
            statement.setString(6, profile.avatarMimeType());
            statement.setLong(7, profile.revision());
            statement.setString(8, updatedAt.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the social profile", exception);
        }
    }

    private SocialProfile map(ResultSet resultSet) throws SQLException {
        return new SocialProfile(
                resultSet.getString("display_name"),
                resultSet.getString("bio"),
                resultSet.getString("comparison_zone_id"),
                DayOfWeek.of(resultSet.getInt("week_start")),
                resultSet.getBytes("avatar_bytes"),
                resultSet.getString("avatar_mime_type"),
                resultSet.getLong("profile_revision"),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
