package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.ConsentChangeEvent;
import com.codefit.peer.identity.ConsentChangeReason;
import com.codefit.peer.protocol.SharingScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The outbox of per-contact consent changes #184's sync layer will eventually drain and publish. */
public class ConsentChangeEventRepository {

    private static final String RECORD_SQL = "INSERT INTO consent_change_events (contact_id, allowed_scopes, "
            + "request_cache_deletion, reason, created_at, synchronized) VALUES (?, ?, ?, ?, ?, 0)";

    public long record(long contactId, List<SharingScope> scopes, boolean requestCacheDeletion,
                        ConsentChangeReason reason, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return record(connection, contactId, scopes, requestCacheDeletion, reason, now);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to record consent change event", exception);
        }
    }

    /** Same effect as {@link #record(long, List, boolean, ConsentChangeReason, Instant)}, on a caller-managed transaction. */
    public long record(Connection connection, long contactId, List<SharingScope> scopes, boolean requestCacheDeletion,
                        ConsentChangeReason reason, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement(RECORD_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, contactId);
            statement.setString(2, encodeScopes(scopes));
            statement.setInt(3, requestCacheDeletion ? 1 : 0);
            statement.setString(4, reason.name());
            statement.setString(5, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to record consent change event", exception);
        }
    }

    public List<ConsentChangeEvent> findByContactId(long contactId) {
        String sql = "SELECT id, contact_id, allowed_scopes, request_cache_deletion, reason, created_at, synchronized "
                + "FROM consent_change_events WHERE contact_id = ? ORDER BY id";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ConsentChangeEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(map(resultSet));
                }
                return events;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load consent change events", exception);
        }
    }

    public List<ConsentChangeEvent> findUnsynchronized() {
        String sql = "SELECT id, contact_id, allowed_scopes, request_cache_deletion, reason, created_at, synchronized "
                + "FROM consent_change_events WHERE synchronized = 0 ORDER BY id";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<ConsentChangeEvent> events = new ArrayList<>();
            while (resultSet.next()) {
                events.add(map(resultSet));
            }
            return events;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load unsynchronized consent change events", exception);
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

    private ConsentChangeEvent map(ResultSet resultSet) throws SQLException {
        return new ConsentChangeEvent(
                resultSet.getLong("id"),
                resultSet.getLong("contact_id"),
                decodeScopes(resultSet.getString("allowed_scopes")),
                resultSet.getInt("request_cache_deletion") != 0,
                ConsentChangeReason.valueOf(resultSet.getString("reason")),
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getInt("synchronized") != 0);
    }
}
