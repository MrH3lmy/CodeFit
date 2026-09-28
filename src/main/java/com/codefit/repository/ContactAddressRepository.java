package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.ContactAddress;
import com.codefit.peer.identity.ContactAddressSource;
import com.codefit.peer.transport.PeerAddress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Persistence for #182's bounded local reachable-address cache per contact. */
public class ContactAddressRepository {

    public List<ContactAddress> findByContactId(long contactId) {
        String sql = "SELECT contact_id, host, port, source, added_at, last_seen_at FROM contact_addresses "
                + "WHERE contact_id = ? ORDER BY last_seen_at DESC";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ContactAddress> addresses = new ArrayList<>();
                while (resultSet.next()) {
                    addresses.add(map(resultSet));
                }
                return addresses;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load cached addresses for contact " + contactId, exception);
        }
    }

    /** Upserts one sighting: a repeat of the same (contact, host, port) just refreshes {@code last_seen_at}. */
    public void recordSighting(long contactId, PeerAddress address, ContactAddressSource source, Instant now) {
        String sql = """
                INSERT INTO contact_addresses (contact_id, host, port, source, added_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(contact_id, host, port) DO UPDATE SET
                    last_seen_at = excluded.last_seen_at,
                    source = excluded.source
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            statement.setString(2, address.host());
            statement.setInt(3, address.port());
            statement.setString(4, source.name());
            statement.setString(5, now.toString());
            statement.setString(6, now.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to record a contact address sighting", exception);
        }
    }

    /** Deletes the oldest-by-last-seen rows for a contact past {@code keep}, enforcing the bounded cache. */
    public void evictOldestBeyond(long contactId, int keep) {
        String sql = """
                DELETE FROM contact_addresses WHERE id IN (
                    SELECT id FROM contact_addresses WHERE contact_id = ?
                    ORDER BY last_seen_at DESC LIMIT -1 OFFSET ?
                )
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            statement.setInt(2, keep);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to evict old cached addresses", exception);
        }
    }

    private ContactAddress map(ResultSet resultSet) throws SQLException {
        return new ContactAddress(
                resultSet.getLong("contact_id"),
                new PeerAddress(resultSet.getString("host"), resultSet.getInt("port")),
                ContactAddressSource.valueOf(resultSet.getString("source")),
                Instant.parse(resultSet.getString("added_at")),
                Instant.parse(resultSet.getString("last_seen_at")));
    }
}
