package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

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
 * Persistence for pinned contacts (#181). {@code identity_id} is pinned once at
 * {@link #insertPending} and never rewritten by any method here: a different key for "the same"
 * contact is always a new row (an explicit re-pair), never an update of this one, matching
 * {@code docs/p2p/protocol-v1.md}'s pinned-key model.
 */
public class ContactRepository {

    public List<Contact> findAll() {
        String sql = "SELECT id, identity_id, identity_public_key, fingerprint, display_name, alias, trust_state, "
                + "created_at, updated_at FROM contacts ORDER BY id";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Contact> contacts = new ArrayList<>();
            while (resultSet.next()) {
                contacts.add(map(resultSet));
            }
            return contacts;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load contacts", exception);
        }
    }

    public Optional<Contact> findById(long id) {
        String sql = "SELECT id, identity_id, identity_public_key, fingerprint, display_name, alias, trust_state, "
                + "created_at, updated_at FROM contacts WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load contact " + id, exception);
        }
    }

    public Optional<Contact> findByIdentityId(IdentityId identityId) {
        String sql = "SELECT id, identity_id, identity_public_key, fingerprint, display_name, alias, trust_state, "
                + "created_at, updated_at FROM contacts WHERE identity_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, identityId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load contact by identity id", exception);
        }
    }

    public long insertPending(IdentityKey identityKey, String fingerprint, String claimedDisplayName, Instant now) {
        String sql = "INSERT INTO contacts (identity_id, identity_public_key, fingerprint, display_name, trust_state, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, identityKey.id().bytes());
            statement.setBytes(2, identityKey.bytes());
            statement.setString(3, fingerprint);
            statement.setString(4, claimedDisplayName);
            statement.setString(5, TrustState.PENDING.name());
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to add contact", exception);
        }
    }

    public void updateTrustState(long id, TrustState trustState, Instant now) {
        update("UPDATE contacts SET trust_state = ?, updated_at = ? WHERE id = ?", statement -> {
            statement.setString(1, trustState.name());
            statement.setString(2, now.toString());
            statement.setLong(3, id);
        });
    }

    /** Same effect as {@link #updateTrustState(long, TrustState, Instant)}, on a caller-managed transaction. */
    public void updateTrustState(Connection connection, long id, TrustState trustState, Instant now) {
        updateOn(connection, "UPDATE contacts SET trust_state = ?, updated_at = ? WHERE id = ?", statement -> {
            statement.setString(1, trustState.name());
            statement.setString(2, now.toString());
            statement.setLong(3, id);
        });
    }

    public void updateCachedDisplayName(long id, String displayName, Instant now) {
        update("UPDATE contacts SET display_name = ?, updated_at = ? WHERE id = ?", statement -> {
            statement.setString(1, displayName);
            statement.setString(2, now.toString());
            statement.setLong(3, id);
        });
    }

    public void updateAlias(long id, String alias, Instant now) {
        update("UPDATE contacts SET alias = ?, updated_at = ? WHERE id = ?", statement -> {
            statement.setString(1, alias);
            statement.setString(2, now.toString());
            statement.setLong(3, id);
        });
    }

    public void delete(long id) {
        update("DELETE FROM contacts WHERE id = ?", statement -> statement.setLong(1, id));
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void update(String sql, Binder binder) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            updateOn(connection, sql, binder);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update contact", exception);
        }
    }

    private void updateOn(Connection connection, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update contact", exception);
        }
    }

    private Contact map(ResultSet resultSet) throws SQLException {
        IdentityKey identityKey = new IdentityKey(resultSet.getBytes("identity_public_key"));
        return new Contact(
                resultSet.getLong("id"),
                identityKey,
                identityKey.id(),
                resultSet.getString("fingerprint"),
                resultSet.getString("display_name"),
                resultSet.getString("alias"),
                TrustState.valueOf(resultSet.getString("trust_state")),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
