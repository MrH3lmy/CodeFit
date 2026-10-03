package com.codefit.repository;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MessageId;
import com.codefit.peer.protocol.ObjectId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * The durable, per-author counterpart of {@code com.codefit.peer.protocol.AuthorReplayState} (#184;
 * see {@code docs/p2p/protocol-v1.md} §10 and that class's own javadoc, which explicitly declare its
 * state in-memory-only and this persistence #184's responsibility). Every method that reads
 * replay-state and every method that records a newly accepted envelope takes an explicit, caller-owned
 * {@link Connection}: the read-then-write across all three tables (author highest epoch, the accepted
 * message/slot row, and the object's latest version) must commit atomically with the envelope's own
 * body being persisted into the peer inbox, in the same transaction {@code PeerSyncIngestService}
 * owns - a crash or rollback between them must never leave the cursor advanced without the evidence
 * durably stored, or vice versa.
 */
public class PeerSyncAuthorStateRepository {

    /** The highest epoch ever accepted from this author, or 0 if none yet (matches {@code AuthorReplayState}'s default). */
    public long highestEpoch(Connection connection, IdentityId author) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT highest_epoch FROM peer_sync_author_state WHERE author_identity_id = ?")) {
            statement.setBytes(1, author.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("highest_epoch") : 0L;
            }
        }
    }

    public boolean hasAccepted(Connection connection, IdentityId author, MessageId messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM peer_sync_accepted_messages WHERE author_identity_id = ? AND message_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, messageId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /** The message id already occupying this author's {@code (epoch, sequence)} slot, if any. */
    public Optional<MessageId> slotOccupant(Connection connection, IdentityId author, long epoch, long sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT message_id FROM peer_sync_accepted_messages "
                        + "WHERE author_identity_id = ? AND epoch = ? AND sequence = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setLong(2, epoch);
            statement.setLong(3, sequence);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(new MessageId(resultSet.getBytes("message_id"))) : Optional.empty();
            }
        }
    }

    /** The latest accepted {@code (epoch, revision, isTombstone)} held for this object, if any. */
    public Optional<ObjectVersion> objectVersion(Connection connection, IdentityId author, ObjectId objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT epoch, revision, is_tombstone FROM peer_sync_object_versions "
                        + "WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ObjectVersion(resultSet.getLong("epoch"), resultSet.getLong("revision"),
                        resultSet.getInt("is_tombstone") != 0));
            }
        }
    }

    /**
     * Records a newly accepted envelope: bumps the author's highest epoch (never lowers it), claims
     * the {@code (epoch, sequence)} slot, and replaces the object's version row. Caller is responsible
     * for having already confirmed acceptance ({@code PeerSyncIngestService} runs this only after
     * replaying the exact §10 rule order and having it come back accepted).
     */
    public void recordAccepted(Connection connection, IdentityId author, MessageId messageId, long epoch, long sequence,
                                ObjectId objectId, long revision, boolean tombstone, Instant expiresAt, Instant now) throws SQLException {
        upsertHighestEpoch(connection, author, epoch, now);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO peer_sync_accepted_messages (author_identity_id, message_id, epoch, sequence, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, messageId.bytes());
            statement.setLong(3, epoch);
            statement.setLong(4, sequence);
            statement.setString(5, expiresAt.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO peer_sync_object_versions (author_identity_id, object_id, epoch, revision, is_tombstone, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(author_identity_id, object_id) DO UPDATE SET "
                        + "epoch = excluded.epoch, revision = excluded.revision, is_tombstone = excluded.is_tombstone, "
                        + "updated_at = excluded.updated_at")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            statement.setLong(3, epoch);
            statement.setLong(4, revision);
            statement.setInt(5, tombstone ? 1 : 0);
            statement.setString(6, now.toString());
            statement.executeUpdate();
        }
    }

    private void upsertHighestEpoch(Connection connection, IdentityId author, long epoch, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO peer_sync_author_state (author_identity_id, highest_epoch, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(author_identity_id) DO UPDATE SET "
                        + "highest_epoch = MAX(highest_epoch, excluded.highest_epoch), updated_at = excluded.updated_at")) {
            statement.setBytes(1, author.bytes());
            statement.setLong(2, epoch);
            statement.setString(3, now.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Prunes accepted-message rows past their {@code expiresAt} (protocol-v1.md §10: "a slot entry may
     * be pruned after its message expires, because expired messages are rejected before the replay
     * checks run"), and object-version rows past {@code retentionCutoff} - never sooner. {@code
     * peer_sync_object_versions} is the anti-resurrection record: pruning it before {@code
     * retentionCutoff} would let an old peer that replays a pre-revocation copy resurrect it, because
     * nothing would remember it was ever tombstoned or superseded.
     */
    public void pruneExpired(Connection connection, Instant now, Instant retentionCutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM peer_sync_accepted_messages WHERE datetime(expires_at) < datetime(?)")) {
            statement.setString(1, now.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM peer_sync_object_versions WHERE datetime(updated_at) < datetime(?)")) {
            statement.setString(1, retentionCutoff.toString());
            statement.executeUpdate();
        }
    }

    public record ObjectVersion(long epoch, long revision, boolean tombstone) {
        /** Lexicographic {@code (epoch, revision)}, matching {@code AuthorReplayState.ObjectVersion}. */
        public boolean isNewerThanOrEqualTo(long otherEpoch, long otherRevision) {
            return epoch > otherEpoch || (epoch == otherEpoch && revision >= otherRevision);
        }
    }
}
