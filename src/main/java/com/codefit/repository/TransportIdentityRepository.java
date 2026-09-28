package com.codefit.repository;

import com.codefit.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Persistence for #182's single local transport-key row, mirroring {@link PeerIdentityRepository}. */
public class TransportIdentityRepository {

    public Optional<TransportIdentityRow> find() {
        String sql = "SELECT transport_public_key, private_key_ciphertext, private_key_salt, private_key_iterations, "
                + "private_key_nonce, binding_valid_from, binding_valid_until, created_at FROM transport_identity WHERE id = 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the local transport identity", exception);
        }
    }

    private static final String REPLACE_SQL = """
            INSERT INTO transport_identity (
                id, transport_public_key, private_key_ciphertext, private_key_salt, private_key_iterations,
                private_key_nonce, binding_valid_from, binding_valid_until, created_at
            ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                transport_public_key = excluded.transport_public_key,
                private_key_ciphertext = excluded.private_key_ciphertext,
                private_key_salt = excluded.private_key_salt,
                private_key_iterations = excluded.private_key_iterations,
                private_key_nonce = excluded.private_key_nonce,
                binding_valid_from = excluded.binding_valid_from,
                binding_valid_until = excluded.binding_valid_until,
                created_at = excluded.created_at
            """;

    public void replace(TransportIdentityRow row) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(REPLACE_SQL)) {
            statement.setBytes(1, row.transportPublicKey());
            statement.setBytes(2, row.privateKeyCiphertext());
            statement.setBytes(3, row.privateKeySalt());
            statement.setInt(4, row.privateKeyIterations());
            statement.setBytes(5, row.privateKeyNonce());
            statement.setString(6, row.bindingValidFrom().toString());
            statement.setString(7, row.bindingValidUntil().toString());
            statement.setString(8, row.createdAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the local transport identity", exception);
        }
    }

    private TransportIdentityRow map(ResultSet resultSet) throws SQLException {
        return new TransportIdentityRow(
                resultSet.getBytes("transport_public_key"),
                resultSet.getBytes("private_key_ciphertext"),
                resultSet.getBytes("private_key_salt"),
                resultSet.getInt("private_key_iterations"),
                resultSet.getBytes("private_key_nonce"),
                Instant.parse(resultSet.getString("binding_valid_from")),
                Instant.parse(resultSet.getString("binding_valid_until")),
                Instant.parse(resultSet.getString("created_at")));
    }
}
