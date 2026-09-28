package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.ObservedTransportBinding;
import com.codefit.peer.protocol.IdentityKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Persistence for #182's last-observed transport-key binding per contact (dial-time pinning). */
public class ContactTransportBindingRepository {

    public Optional<ObservedTransportBinding> find(long contactId) {
        String sql = "SELECT contact_id, transport_public_key, valid_from, valid_until, observed_at "
                + "FROM contact_transport_bindings WHERE contact_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the observed transport binding for contact " + contactId, exception);
        }
    }

    public void save(ObservedTransportBinding binding) {
        String sql = """
                INSERT INTO contact_transport_bindings (contact_id, transport_public_key, valid_from, valid_until, observed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(contact_id) DO UPDATE SET
                    transport_public_key = excluded.transport_public_key,
                    valid_from = excluded.valid_from,
                    valid_until = excluded.valid_until,
                    observed_at = excluded.observed_at
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, binding.contactId());
            statement.setBytes(2, binding.transportKey().bytes());
            statement.setString(3, binding.validFrom().toString());
            statement.setString(4, binding.validUntil().toString());
            statement.setString(5, binding.observedAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the observed transport binding", exception);
        }
    }

    private ObservedTransportBinding map(ResultSet resultSet) throws SQLException {
        return new ObservedTransportBinding(
                resultSet.getLong("contact_id"),
                new IdentityKey(resultSet.getBytes("transport_public_key")),
                Instant.parse(resultSet.getString("valid_from")),
                Instant.parse(resultSet.getString("valid_until")),
                Instant.parse(resultSet.getString("observed_at")));
    }
}
