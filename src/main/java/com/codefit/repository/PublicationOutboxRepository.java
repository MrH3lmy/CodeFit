package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.MessageType;
import com.codefit.peer.sync.PublicationOutboxEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The learner's explicit approvals for ongoing sharing with one contact (#184). An approval is the
 * only thing that makes a logical object (one window's {@code PROGRESS_SUMMARY}, or one profile's
 * {@code PREPARATION_SNAPSHOT}) eligible for sync at all - sharing is never automatic from evidence
 * and a grant alone. {@code last_synced_revision}/{@code last_synced_at} are optimistic, UI-facing
 * hints only (see {@code PeerSyncOutboxService}); nothing here is ever consulted to decide whether to
 * (re)send an object - only whether a {@code Tombstone} is owed if authorization is later withdrawn.
 */
public class PublicationOutboxRepository {

    /** Idempotent: approving the same (contact, type, logicalKey) again is a no-op. */
    public PublicationOutboxEntry approve(long contactId, MessageType messageType, String logicalKey, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO publication_outbox (contact_id, message_type, logical_key, approved_at) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(contact_id, message_type, logical_key) DO NOTHING")) {
                statement.setLong(1, contactId);
                statement.setString(2, messageType.name());
                statement.setString(3, logicalKey);
                statement.setString(4, now.toString());
                statement.executeUpdate();
            }
            return findOne(connection, contactId, messageType, logicalKey)
                    .orElseThrow(() -> new IllegalStateException("Outbox row vanished immediately after insert"));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to approve publication outbox entry", exception);
        }
    }

    public List<PublicationOutboxEntry> findByContact(long contactId) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM publication_outbox WHERE contact_id = ? ORDER BY id")) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PublicationOutboxEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(map(resultSet));
                }
                return entries;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load publication outbox", exception);
        }
    }

    public List<PublicationOutboxEntry> findAll() {
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM publication_outbox ORDER BY id")) {
            List<PublicationOutboxEntry> entries = new ArrayList<>();
            while (resultSet.next()) {
                entries.add(map(resultSet));
            }
            return entries;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load publication outbox", exception);
        }
    }

    /**
     * Optimistic, UI-facing only: records that a full, clean bidirectional sync session believed this
     * revision reached the peer. Never gates what gets (re)sent - see the class javadoc.
     */
    public void markSynced(long id, long revision, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE publication_outbox SET last_synced_revision = ?, last_synced_at = ? WHERE id = ?")) {
            statement.setLong(1, revision);
            statement.setString(2, now.toString());
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update publication outbox sync state", exception);
        }
    }

    private Optional<PublicationOutboxEntry> findOne(Connection connection, long contactId, MessageType messageType,
                                                       String logicalKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM publication_outbox WHERE contact_id = ? AND message_type = ? AND logical_key = ?")) {
            statement.setLong(1, contactId);
            statement.setString(2, messageType.name());
            statement.setString(3, logicalKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private PublicationOutboxEntry map(ResultSet resultSet) throws SQLException {
        String lastSyncedAt = resultSet.getString("last_synced_at");
        return new PublicationOutboxEntry(
                resultSet.getLong("id"),
                resultSet.getLong("contact_id"),
                MessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("logical_key"),
                Instant.parse(resultSet.getString("approved_at")),
                resultSet.getObject("last_synced_revision") == null ? null : resultSet.getLong("last_synced_revision"),
                lastSyncedAt == null ? null : Instant.parse(lastSyncedAt));
    }
}
