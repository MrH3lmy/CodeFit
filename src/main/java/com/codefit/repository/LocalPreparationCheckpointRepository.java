package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.DomainSnapshot;
import com.codefit.peer.protocol.DomainStatus;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationStatus;
import com.codefit.peer.snapshot.LocalPreparationCheckpoint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists #183's own local, dated {@link LocalPreparationCheckpoint} captures. At most one row per
 * {@code (profile_id, checkpoint_date)} UTC day; a same-day re-capture only ever {@link #save replaces}
 * it when strictly newer, a different day is a new permanent historical row (never derived from
 * today's readiness - see {@code PreparationSnapshotCaptureService}). Namespaced apart from any future
 * #184 received/cached peer snapshot store.
 */
public class LocalPreparationCheckpointRepository {

    /** What {@link #save} did with a captured checkpoint. */
    public enum SaveOutcome {
        RECORDED_FIRST,
        REPLACED,
        IGNORED_STALE
    }

    public SaveOutcome save(LocalPreparationCheckpoint checkpoint) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SaveOutcome outcome = save(connection, checkpoint);
                connection.commit();
                return outcome;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save local preparation checkpoint", exception);
        }
    }

    private SaveOutcome save(Connection connection, LocalPreparationCheckpoint checkpoint) throws SQLException {
        Optional<Long> existingId = findRowId(connection, checkpoint.profileId(), checkpoint.checkpointDateUtc());
        if (existingId.isPresent()) {
            long existingRevision = findRevision(connection, existingId.get());
            if (checkpoint.revision() <= existingRevision) {
                return SaveOutcome.IGNORED_STALE;
            }
            deleteRow(connection, existingId.get());
        }
        long id = insertCheckpointRow(connection, checkpoint);
        insertDomains(connection, id, checkpoint.snapshot().domains());
        return existingId.isPresent() ? SaveOutcome.REPLACED : SaveOutcome.RECORDED_FIRST;
    }

    /** The checkpoint for this exact UTC day, if one was ever captured - never reconstructed from another day. */
    public Optional<LocalPreparationCheckpoint> findByDate(String profileId, LocalDate checkpointDateUtc) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            Optional<Long> id = findRowId(connection, profileId, checkpointDateUtc);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(load(connection, id.get(), profileId, checkpointDateUtc));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load local preparation checkpoint", exception);
        }
    }

    /** Every checkpoint ever captured for this profile, oldest first - the real historical trend. */
    public List<LocalPreparationCheckpoint> findAllForProfile(String profileId) {
        List<LocalPreparationCheckpoint> result = new ArrayList<>();
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, checkpoint_date FROM local_preparation_checkpoints WHERE profile_id = ? ORDER BY checkpoint_date")) {
            statement.setString(1, profileId);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new Object[]{resultSet.getLong("id"), LocalDate.parse(resultSet.getString("checkpoint_date"))});
                }
            }
            for (Object[] row : rows) {
                result.add(load(connection, (Long) row[0], profileId, (LocalDate) row[1]));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load preparation checkpoints", exception);
        }
        return result;
    }

    private Optional<Long> findRowId(Connection connection, String profileId, LocalDate checkpointDateUtc) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM local_preparation_checkpoints WHERE profile_id = ? AND checkpoint_date = ?")) {
            statement.setString(1, profileId);
            statement.setString(2, checkpointDateUtc.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong("id")) : Optional.empty();
            }
        }
    }

    private long findRevision(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM local_preparation_checkpoints WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("revision");
            }
        }
    }

    /**
     * Deletes the checkpoint row and its domain children explicitly, rather than relying on the
     * schema's {@code ON DELETE CASCADE} alone: SQLite only enforces foreign keys when a connection has
     * run {@code PRAGMA foreign_keys = ON}, which {@code DatabaseConfig.getConnection()} does not do
     * for every connection it hands out, so a cascade cannot be assumed here.
     */
    private void deleteRow(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM local_preparation_checkpoint_domains WHERE checkpoint_id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM local_preparation_checkpoints WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private long insertCheckpointRow(Connection connection, LocalPreparationCheckpoint checkpoint) throws SQLException {
        PreparationSnapshot snapshot = checkpoint.snapshot();
        String sql = "INSERT INTO local_preparation_checkpoints (profile_id, checkpoint_date, revision, "
                + "profile_fingerprint, scoring_version, overall_threshold_percent, captured_at, overall_percent, "
                + "coverage_percent, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, checkpoint.profileId());
            statement.setString(2, checkpoint.checkpointDateUtc().toString());
            statement.setLong(3, checkpoint.revision());
            statement.setBytes(4, snapshot.profileFingerprint());
            statement.setInt(5, snapshot.scoringVersion());
            statement.setInt(6, snapshot.overallThresholdPercent());
            statement.setString(7, snapshot.capturedAt().toString());
            setNullableInt(statement, 8, snapshot.overallPercent());
            statement.setInt(9, snapshot.coveragePercent());
            statement.setString(10, snapshot.status().name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertDomains(Connection connection, long checkpointId, List<DomainSnapshot> domains) throws SQLException {
        String sql = "INSERT INTO local_preparation_checkpoint_domains (checkpoint_id, domain_order, domain_id, "
                + "weight_percent, critical_gate, threshold_percent, score_percent, coverage_percent, "
                + "measured_requirement_count, total_requirement_count, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int order = 0;
            for (DomainSnapshot domain : domains) {
                statement.setLong(1, checkpointId);
                statement.setInt(2, order++);
                statement.setString(3, domain.domainId());
                statement.setInt(4, domain.weightPercent());
                statement.setInt(5, domain.criticalGate() ? 1 : 0);
                setNullableInt(statement, 6, domain.thresholdPercent());
                setNullableInt(statement, 7, domain.scorePercent());
                statement.setInt(8, domain.coveragePercent());
                statement.setInt(9, domain.measuredRequirementCount());
                statement.setInt(10, domain.totalRequirementCount());
                statement.setString(11, domain.status().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private LocalPreparationCheckpoint load(Connection connection, long id, String profileId, LocalDate checkpointDateUtc)
            throws SQLException {
        long revision;
        byte[] fingerprint;
        int scoringVersion;
        int overallThresholdPercent;
        Instant capturedAt;
        Integer overallPercent;
        int coveragePercent;
        PreparationStatus status;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, profile_fingerprint, scoring_version, overall_threshold_percent, captured_at, "
                        + "overall_percent, coverage_percent, status FROM local_preparation_checkpoints WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                revision = resultSet.getLong("revision");
                fingerprint = resultSet.getBytes("profile_fingerprint");
                scoringVersion = resultSet.getInt("scoring_version");
                overallThresholdPercent = resultSet.getInt("overall_threshold_percent");
                capturedAt = Instant.parse(resultSet.getString("captured_at"));
                overallPercent = nullableInt(resultSet, "overall_percent");
                coveragePercent = resultSet.getInt("coverage_percent");
                status = PreparationStatus.valueOf(resultSet.getString("status"));
            }
        }
        List<DomainSnapshot> domains = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT domain_id, weight_percent, critical_gate, threshold_percent, score_percent, coverage_percent, "
                        + "measured_requirement_count, total_requirement_count, status FROM local_preparation_checkpoint_domains "
                        + "WHERE checkpoint_id = ? ORDER BY domain_order")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    domains.add(new DomainSnapshot(
                            resultSet.getString("domain_id"),
                            resultSet.getInt("weight_percent"),
                            resultSet.getInt("critical_gate") == 1,
                            nullableInt(resultSet, "threshold_percent"),
                            nullableInt(resultSet, "score_percent"),
                            resultSet.getInt("coverage_percent"),
                            resultSet.getInt("measured_requirement_count"),
                            resultSet.getInt("total_requirement_count"),
                            DomainStatus.valueOf(resultSet.getString("status"))));
                }
            }
        }
        PreparationSnapshot snapshot = new PreparationSnapshot(profileId, fingerprint, scoringVersion,
                overallThresholdPercent, capturedAt, overallPercent, coveragePercent, status, domains);
        return new LocalPreparationCheckpoint(profileId, checkpointDateUtc, revision, snapshot);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
