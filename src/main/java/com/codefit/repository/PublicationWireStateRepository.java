package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.ObjectId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;

/**
 * The strictly increasing revision counter for {@code CONSENT_REVISION}/{@code Tombstone} envelopes
 * this device signs (#184). Same atomic read-compare-write-under-a-lock shape as {@code
 * PreparationSnapshotWireStateRepository} (#183), and kept as its own table for the identical reason
 * that one is its own table rather than reusing another: a different logical object domain must never
 * share a revision counter with another, or two unrelated objects' first-ever publish could each
 * independently claim revision 1 and collide.
 */
public class PublicationWireStateRepository {

    private static final Object REVISION_LOCK = new Object();

    public long nextRevision(ObjectId objectId, byte[] bodyFingerprint) {
        synchronized (REVISION_LOCK) {
            try (Connection connection = DatabaseConfig.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    long revision = nextRevision(connection, objectId, bodyFingerprint);
                    connection.commit();
                    return revision;
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to assign a publication wire revision", exception);
            }
        }
    }

    private long nextRevision(Connection connection, ObjectId objectId, byte[] bodyFingerprint) throws SQLException {
        Optional<Existing> existing = find(connection, objectId);
        if (existing.isPresent() && Arrays.equals(existing.get().fingerprint(), bodyFingerprint)) {
            return existing.get().revision();
        }
        long newRevision = existing.map(Existing::revision).orElse(0L) + 1;
        upsert(connection, objectId, newRevision, bodyFingerprint);
        return newRevision;
    }

    private record Existing(long revision, byte[] fingerprint) {
    }

    private Optional<Existing> find(Connection connection, ObjectId objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, body_fingerprint FROM publication_wire_state WHERE object_id = ?")) {
            statement.setBytes(1, objectId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Existing(resultSet.getLong("revision"), resultSet.getBytes("body_fingerprint")));
            }
        }
    }

    private void upsert(Connection connection, ObjectId objectId, long revision, byte[] bodyFingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO publication_wire_state (object_id, revision, body_fingerprint) VALUES (?, ?, ?)")) {
            statement.setBytes(1, objectId.bytes());
            statement.setLong(2, revision);
            statement.setBytes(3, bodyFingerprint);
            statement.executeUpdate();
        }
    }
}
