package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.DomainSnapshot;
import com.codefit.peer.protocol.DomainStatus;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationStatus;
import com.codefit.peer.sync.PeerPreparationSnapshot;

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
 * The validated peer inbox for {@code PREPARATION_SNAPSHOT} (#184). Latest-revision-only per
 * {@code (author, objectId)}, completely separate from this device's own {@code
 * local_preparation_checkpoints} (#183) - never read by {@code InterviewReadinessService} or any
 * local readiness/mastery computation.
 */
public class PeerPreparationSnapshotRepository {

    public void upsert(Connection connection, IdentityId author, ObjectId objectId, long epoch, long revision,
                        PreparationSnapshot body, Instant receivedAt) throws SQLException {
        long snapshotId;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM peer_preparation_snapshots WHERE author_identity_id = ? AND object_id = ?")) {
            delete.setBytes(1, author.bytes());
            delete.setBytes(2, objectId.bytes());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO peer_preparation_snapshots (author_identity_id, object_id, epoch, revision, profile_id, "
                        + "profile_fingerprint, scoring_version, overall_threshold_percent, captured_at, overall_percent, "
                        + "coverage_percent, status, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setBytes(1, author.bytes());
            insert.setBytes(2, objectId.bytes());
            insert.setLong(3, epoch);
            insert.setLong(4, revision);
            insert.setString(5, body.profileId());
            insert.setBytes(6, body.profileFingerprint());
            insert.setInt(7, body.scoringVersion());
            insert.setInt(8, body.overallThresholdPercent());
            insert.setString(9, body.capturedAt().toString());
            if (body.overallPercent() == null) {
                insert.setNull(10, java.sql.Types.INTEGER);
            } else {
                insert.setInt(10, body.overallPercent());
            }
            insert.setInt(11, body.coveragePercent());
            insert.setString(12, body.status().name());
            insert.setString(13, receivedAt.toString());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                snapshotId = keys.getLong(1);
            }
        }
        try (PreparedStatement insertDomain = connection.prepareStatement(
                "INSERT INTO peer_preparation_snapshot_domains (snapshot_id, domain_order, domain_id, weight_percent, "
                        + "critical_gate, threshold_percent, score_percent, coverage_percent, measured_requirement_count, "
                        + "total_requirement_count, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            List<DomainSnapshot> domains = body.domains();
            for (int i = 0; i < domains.size(); i++) {
                DomainSnapshot domain = domains.get(i);
                insertDomain.setLong(1, snapshotId);
                insertDomain.setInt(2, i);
                insertDomain.setString(3, domain.domainId());
                insertDomain.setInt(4, domain.weightPercent());
                insertDomain.setInt(5, domain.criticalGate() ? 1 : 0);
                setNullableInt(insertDomain, 6, domain.thresholdPercent());
                setNullableInt(insertDomain, 7, domain.scorePercent());
                insertDomain.setInt(8, domain.coveragePercent());
                insertDomain.setInt(9, domain.measuredRequirementCount());
                insertDomain.setInt(10, domain.totalRequirementCount());
                insertDomain.setString(11, domain.status().name());
                insertDomain.executeUpdate();
            }
        }
    }

    /** Deletes the cached copy for this object, if any - a tombstone's cache-deletion effect. */
    public void delete(Connection connection, IdentityId author, ObjectId objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM peer_preparation_snapshots WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            statement.executeUpdate();
        }
    }

    public Optional<PeerPreparationSnapshot> findByAuthorAndObjectId(IdentityId author, ObjectId objectId) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM peer_preparation_snapshots WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(connection, resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load peer preparation snapshot", exception);
        }
    }

    public List<PeerPreparationSnapshot> findByAuthor(IdentityId author) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM peer_preparation_snapshots WHERE author_identity_id = ? ORDER BY captured_at")) {
            statement.setBytes(1, author.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PeerPreparationSnapshot> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(map(connection, resultSet));
                }
                return results;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load peer preparation snapshots", exception);
        }
    }

    private PeerPreparationSnapshot map(Connection connection, ResultSet resultSet) throws SQLException {
        long snapshotId = resultSet.getLong("id");
        List<DomainSnapshot> domains = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM peer_preparation_snapshot_domains WHERE snapshot_id = ? ORDER BY domain_order")) {
            statement.setLong(1, snapshotId);
            try (ResultSet domainRows = statement.executeQuery()) {
                while (domainRows.next()) {
                    domains.add(new DomainSnapshot(
                            domainRows.getString("domain_id"),
                            domainRows.getInt("weight_percent"),
                            domainRows.getInt("critical_gate") != 0,
                            nullableInt(domainRows, "threshold_percent"),
                            nullableInt(domainRows, "score_percent"),
                            domainRows.getInt("coverage_percent"),
                            domainRows.getInt("measured_requirement_count"),
                            domainRows.getInt("total_requirement_count"),
                            DomainStatus.valueOf(domainRows.getString("status"))));
                }
            }
        }
        PreparationSnapshot body = new PreparationSnapshot(
                resultSet.getString("profile_id"),
                resultSet.getBytes("profile_fingerprint"),
                resultSet.getInt("scoring_version"),
                resultSet.getInt("overall_threshold_percent"),
                Instant.parse(resultSet.getString("captured_at")),
                nullableInt(resultSet, "overall_percent"),
                resultSet.getInt("coverage_percent"),
                PreparationStatus.valueOf(resultSet.getString("status")),
                domains);
        return new PeerPreparationSnapshot(new IdentityId(resultSet.getBytes("author_identity_id")),
                new ObjectId(resultSet.getBytes("object_id")), resultSet.getLong("epoch"), resultSet.getLong("revision"),
                body, Instant.parse(resultSet.getString("received_at")));
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
