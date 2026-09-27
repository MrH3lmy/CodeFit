package com.codefit.repository;

import com.codefit.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for #181's single local identity row. {@code peer_identity} is a singleton
 * ({@code id = 1}): every write here targets that one row, and {@link #replace} is the only
 * operation allowed to touch the key-material columns, so creation, rotation, and restore are the
 * only three code paths in this class that ever write ciphertext.
 */
public class PeerIdentityRepository {

    public Optional<PeerIdentityRow> find() {
        String sql = "SELECT identity_public_key, private_key_ciphertext, private_key_salt, private_key_iterations, "
                + "private_key_nonce, key_format_version, highest_known_epoch, current_writer_epoch, "
                + "sharing_paused, created_at, last_restored_at FROM peer_identity WHERE id = 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the local peer identity", exception);
        }
    }

    private static final String REPLACE_SQL = """
            INSERT INTO peer_identity (
                id, identity_public_key, private_key_ciphertext, private_key_salt, private_key_iterations,
                private_key_nonce, key_format_version, highest_known_epoch, current_writer_epoch,
                sharing_paused, created_at, last_restored_at
            ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                identity_public_key = excluded.identity_public_key,
                private_key_ciphertext = excluded.private_key_ciphertext,
                private_key_salt = excluded.private_key_salt,
                private_key_iterations = excluded.private_key_iterations,
                private_key_nonce = excluded.private_key_nonce,
                key_format_version = excluded.key_format_version,
                highest_known_epoch = excluded.highest_known_epoch,
                current_writer_epoch = excluded.current_writer_epoch,
                sharing_paused = excluded.sharing_paused,
                created_at = excluded.created_at,
                last_restored_at = excluded.last_restored_at
            """;

    /** Creates or wholesale replaces the singleton identity row: identity creation, rotation, and restore. */
    public void replace(PeerIdentityRow row) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            replace(connection, row);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the local peer identity", exception);
        }
    }

    /** Same effect as {@link #replace(PeerIdentityRow)}, on a caller-managed transaction. */
    public void replace(Connection connection, PeerIdentityRow row) {
        try (PreparedStatement statement = connection.prepareStatement(REPLACE_SQL)) {
            statement.setBytes(1, row.identityPublicKey());
            statement.setBytes(2, row.privateKeyCiphertext());
            statement.setBytes(3, row.privateKeySalt());
            statement.setInt(4, row.privateKeyIterations());
            statement.setBytes(5, row.privateKeyNonce());
            statement.setInt(6, row.keyFormatVersion());
            statement.setLong(7, row.highestKnownEpoch());
            statement.setLong(8, row.currentWriterEpoch());
            statement.setInt(9, row.sharingPaused() ? 1 : 0);
            statement.setString(10, row.createdAt().toString());
            statement.setString(11, row.lastRestoredAt() == null ? null : row.lastRestoredAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the local peer identity", exception);
        }
    }

    /** Updates only the writer-epoch bookkeeping, e.g. beginning a new session that is not a restore. */
    public void updateEpochState(long highestKnownEpoch, long currentWriterEpoch) {
        String sql = "UPDATE peer_identity SET highest_known_epoch = ?, current_writer_epoch = ? WHERE id = 1";
        executeUpdate(sql, statement -> {
            statement.setLong(1, highestKnownEpoch);
            statement.setLong(2, currentWriterEpoch);
        });
    }

    public void setSharingPaused(boolean paused) {
        String sql = "UPDATE peer_identity SET sharing_paused = ? WHERE id = 1";
        executeUpdate(sql, statement -> statement.setInt(1, paused ? 1 : 0));
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void executeUpdate(String sql, Binder binder) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update the local peer identity", exception);
        }
    }

    private PeerIdentityRow map(ResultSet resultSet) throws SQLException {
        return new PeerIdentityRow(
                resultSet.getBytes("identity_public_key"),
                resultSet.getBytes("private_key_ciphertext"),
                resultSet.getBytes("private_key_salt"),
                resultSet.getInt("private_key_iterations"),
                resultSet.getBytes("private_key_nonce"),
                resultSet.getInt("key_format_version"),
                resultSet.getLong("highest_known_epoch"),
                resultSet.getLong("current_writer_epoch"),
                resultSet.getInt("sharing_paused") != 0,
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getString("last_restored_at") == null ? null : Instant.parse(resultSet.getString("last_restored_at")));
    }
}
