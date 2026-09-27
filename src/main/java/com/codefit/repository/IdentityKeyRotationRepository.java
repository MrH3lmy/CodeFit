package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.KeyContinuityRecord;
import com.codefit.peer.protocol.IdentityKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Append-only history of signed key-continuity records produced by {@code IdentityService.rotateKeyWithContinuity}. */
public class IdentityKeyRotationRepository {

    public Optional<KeyContinuityRecord> findLatest() {
        String sql = "SELECT old_public_key, new_public_key, rotated_at, continuity_signature FROM identity_key_rotations "
                + "ORDER BY id DESC LIMIT 1";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new KeyContinuityRecord(
                    new IdentityKey(resultSet.getBytes("old_public_key")),
                    new IdentityKey(resultSet.getBytes("new_public_key")),
                    Instant.parse(resultSet.getString("rotated_at")),
                    resultSet.getBytes("continuity_signature")));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the latest key continuity record", exception);
        }
    }

    public void save(KeyContinuityRecord record) {
        String sql = "INSERT INTO identity_key_rotations (old_public_key, new_public_key, rotated_at, continuity_signature) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, record.oldKey().bytes());
            statement.setBytes(2, record.newKey().bytes());
            statement.setString(3, record.rotatedAt().toString());
            statement.setBytes(4, record.continuitySignature());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save the key continuity record", exception);
        }
    }
}
