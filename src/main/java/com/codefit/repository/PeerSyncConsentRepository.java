package com.codefit.repository;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.SharingScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This receiver's cached view of each remote author's latest accepted {@code CONSENT_REVISION}: what
 * scopes <em>they</em> currently grant <em>this device</em>. {@code docs/p2p/protocol-v1.md} §10:
 * "Whether the author's latest CONSENT_REVISION to this receiver grants the body's required scope is
 * checked by the sync layer (#184), which owns persisted consent." Distinct from {@code
 * contact_permissions} (#181), which is the opposite direction: what scopes <em>this device</em>
 * grants a contact.
 */
public class PeerSyncConsentRepository {

    public Optional<List<SharingScope>> scopesFor(Connection connection, IdentityId author) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT allowed_scopes FROM peer_sync_consent WHERE author_identity_id = ?")) {
            statement.setBytes(1, author.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(decode(resultSet.getString("allowed_scopes"))) : Optional.empty();
            }
        }
    }

    public void save(Connection connection, IdentityId author, List<SharingScope> scopes, long epoch, long revision, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO peer_sync_consent (author_identity_id, allowed_scopes, epoch, revision, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(author_identity_id) DO UPDATE SET "
                        + "allowed_scopes = excluded.allowed_scopes, epoch = excluded.epoch, revision = excluded.revision, "
                        + "updated_at = excluded.updated_at")) {
            statement.setBytes(1, author.bytes());
            statement.setString(2, encode(scopes));
            statement.setLong(3, epoch);
            statement.setLong(4, revision);
            statement.setString(5, now.toString());
            statement.executeUpdate();
        }
    }

    private static String encode(List<SharingScope> scopes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(scopes.get(i).name());
        }
        return builder.toString();
    }

    private static List<SharingScope> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<SharingScope> scopes = new ArrayList<>();
        for (String name : encoded.split(",")) {
            scopes.add(SharingScope.valueOf(name));
        }
        return scopes;
    }
}
