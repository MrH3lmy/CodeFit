package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.protocol.SharingScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for the per-contact sharing grant (#181). One row per contact, upserted on every change. */
public class ContactPermissionRepository {

    public Optional<ContactPermission> find(long contactId) {
        String sql = "SELECT contact_id, allowed_scopes, historical_window_days, expires_at, allow_forwarding, "
                + "revision, updated_at FROM contact_permissions WHERE contact_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load sharing permission for contact " + contactId, exception);
        }
    }

    private static final String SAVE_SQL = """
            INSERT INTO contact_permissions (
                contact_id, allowed_scopes, historical_window_days, expires_at, allow_forwarding, revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(contact_id) DO UPDATE SET
                allowed_scopes = excluded.allowed_scopes,
                historical_window_days = excluded.historical_window_days,
                expires_at = excluded.expires_at,
                allow_forwarding = excluded.allow_forwarding,
                revision = excluded.revision,
                updated_at = excluded.updated_at
            """;

    public void save(ContactPermission permission) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            save(connection, permission);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save sharing permission", exception);
        }
    }

    /** Same effect as {@link #save(ContactPermission)}, on a caller-managed transaction. */
    public void save(Connection connection, ContactPermission permission) {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_SQL)) {
            statement.setLong(1, permission.contactId());
            statement.setString(2, encodeScopes(permission.scopes()));
            if (permission.historicalWindowDays() == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, permission.historicalWindowDays());
            }
            statement.setString(4, permission.expiresAt() == null ? null : permission.expiresAt().toString());
            statement.setInt(5, permission.allowForwarding() ? 1 : 0);
            statement.setLong(6, permission.revision());
            statement.setString(7, permission.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save sharing permission", exception);
        }
    }

    private static String encodeScopes(List<SharingScope> scopes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(scopes.get(i).name());
        }
        return builder.toString();
    }

    private static List<SharingScope> decodeScopes(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<SharingScope> scopes = new ArrayList<>();
        for (String name : encoded.split(",")) {
            scopes.add(SharingScope.valueOf(name));
        }
        return scopes;
    }

    private ContactPermission map(ResultSet resultSet) throws SQLException {
        String expiresAt = resultSet.getString("expires_at");
        int rawHistoricalWindowDays = resultSet.getInt("historical_window_days");
        Integer historicalWindowDays = resultSet.wasNull() ? null : rawHistoricalWindowDays;
        return new ContactPermission(
                resultSet.getLong("contact_id"),
                decodeScopes(resultSet.getString("allowed_scopes")),
                historicalWindowDays,
                expiresAt == null ? null : Instant.parse(expiresAt),
                resultSet.getInt("allow_forwarding") != 0,
                resultSet.getLong("revision"),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
