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
 * The strictly increasing revision counter for the <em>wire</em> preparation-snapshot object (keyed by
 * {@code object_id}, i.e. by author/recipient/profile — see {@code SchemaMigrator.createPreparationSnapshotWireStateTable}
 * for why this must be a separate counter from the per-day local checkpoint's own revision). Atomic
 * per call: reads the current (revision, fingerprint) for this object id and either reuses the
 * existing revision (fingerprint unchanged — an idempotent resend) or assigns the next one (fingerprint
 * changed, or no row yet), all inside one transaction.
 *
 * <p>The read-compare-write runs inside {@link #REVISION_LOCK} (#183 review fix), the same reason
 * {@code LocalProgressSnapshotRepository}/{@code LocalPreparationCheckpointRepository} do: two threads
 * on separate connections could otherwise both read the same (revision, fingerprint) before either
 * commits and independently compute the same "next" revision. This is always called from inside
 * {@code SnapshotPublicationService}'s own {@code synchronized (ContactService.PERMISSION_LOCK)} block,
 * so the lock ordering here is always {@code PERMISSION_LOCK} (outer) then this one (inner), never the
 * reverse - this class never tries to acquire {@code PERMISSION_LOCK} itself.
 */
public class PreparationSnapshotWireStateRepository {

    /** Serializes every read-compare-write revision assignment in this class; see the class Javadoc. */
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
                throw new IllegalStateException("Unable to assign a preparation snapshot wire revision", exception);
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
                "SELECT revision, body_fingerprint FROM preparation_snapshot_wire_state WHERE object_id = ?")) {
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
                "INSERT OR REPLACE INTO preparation_snapshot_wire_state (object_id, revision, body_fingerprint) "
                        + "VALUES (?, ?, ?)")) {
            statement.setBytes(1, objectId.bytes());
            statement.setLong(2, revision);
            statement.setBytes(3, bodyFingerprint);
            statement.executeUpdate();
        }
    }
}
