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
 * {@code (profile_id, checkpoint_date)} UTC day; a same-day re-capture either {@link #save replaces} it
 * (the readiness content genuinely changed) or leaves it untouched (an idempotent resend of the
 * identical content), and a different day is a new permanent historical row. Namespaced apart from any
 * future #184 received/cached peer snapshot store.
 *
 * <p>{@code revision} is assigned here, not by the caller, exactly like
 * {@code LocalProgressSnapshotRepository}: the first capture for a {@code (profileId, checkpointDateUtc)}
 * pair is revision 1, and a later same-day capture whose content actually differs is
 * {@code existingRevision + 1} — never derived from {@code capturedAt}'s timestamp, which could collide
 * across two real, distinct same-day captures made within the same wall-clock second. This LOCAL,
 * per-day revision is deliberately a different counter from the WIRE preparation-snapshot object's own
 * revision (see {@code PreparationSnapshotWireStateRepository}): the wire object is keyed only by
 * profile (an evolving "latest readiness" stream spanning many days), so reusing this per-day revision
 * as the wire revision would let two different days each restart at revision 1 and collide on the wire.
 *
 * <p>The read-compare-write that assigns a revision runs inside {@link #REVISION_LOCK} (#183 review
 * fix) for the same reason {@code LocalProgressSnapshotRepository} does: two threads on separate
 * connections could otherwise both read the same existing revision before either commits and compute
 * the same "next" revision independently, silently losing whichever one commits first.
 */
public class LocalPreparationCheckpointRepository {

    /** Serializes every read-compare-write revision assignment in this class; see the class Javadoc. */
    private static final Object REVISION_LOCK = new Object();

    /** What {@link #save} did with a captured checkpoint. */
    public enum SaveOutcome {
        /** No checkpoint existed yet for this (profile, day); this one is now recorded as revision 1. */
        RECORDED_FIRST,
        /** A checkpoint already existed for this (profile, day) with different content; it was replaced. */
        REPLACED,
        /** A checkpoint already existed for this (profile, day) with identical content; nothing changed. */
        UNCHANGED
    }

    /** The outcome of {@link #save}, and the checkpoint as actually persisted (with its real, assigned revision). */
    public record SaveResult(SaveOutcome outcome, LocalPreparationCheckpoint checkpoint) {
    }

    public SaveResult save(String profileId, LocalDate checkpointDateUtc, PreparationSnapshot snapshot) {
        synchronized (REVISION_LOCK) {
            try (Connection connection = DatabaseConfig.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    SaveResult result = save(connection, profileId, checkpointDateUtc, snapshot);
                    connection.commit();
                    return result;
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
    }

    private SaveResult save(Connection connection, String profileId, LocalDate checkpointDateUtc, PreparationSnapshot snapshot)
            throws SQLException {
        Optional<Long> existingId = findRowId(connection, profileId, checkpointDateUtc);
        if (existingId.isPresent()) {
            LocalPreparationCheckpoint existing = load(connection, existingId.get(), profileId, checkpointDateUtc);
            if (sameContent(existing.snapshot(), snapshot)) {
                return new SaveResult(SaveOutcome.UNCHANGED, existing);
            }
            long newRevision = existing.revision() + 1;
            deleteRow(connection, existingId.get());
            LocalPreparationCheckpoint persisted = new LocalPreparationCheckpoint(profileId, checkpointDateUtc, newRevision, snapshot);
            long id = insertCheckpointRow(connection, persisted);
            insertDomains(connection, id, persisted.snapshot().domains());
            return new SaveResult(SaveOutcome.REPLACED, persisted);
        }
        LocalPreparationCheckpoint persisted = new LocalPreparationCheckpoint(profileId, checkpointDateUtc, 1, snapshot);
        long id = insertCheckpointRow(connection, persisted);
        insertDomains(connection, id, persisted.snapshot().domains());
        return new SaveResult(SaveOutcome.RECORDED_FIRST, persisted);
    }

    /** Content equality ignoring {@code capturedAt}, which always differs between two real captures. */
    private static boolean sameContent(PreparationSnapshot a, PreparationSnapshot b) {
        return a.scoringVersion() == b.scoringVersion()
                && a.overallThresholdPercent() == b.overallThresholdPercent()
                && java.util.Arrays.equals(a.profileFingerprint(), b.profileFingerprint())
                && java.util.Objects.equals(a.overallPercent(), b.overallPercent())
                && a.coveragePercent() == b.coveragePercent()
                && a.status() == b.status()
                && a.domains().equals(b.domains());
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
