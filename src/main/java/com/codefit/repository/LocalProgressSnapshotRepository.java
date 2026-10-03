package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.snapshot.LocalProgressSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persists #183's own local, dated {@link LocalProgressSnapshot} captures — never a received peer
 * snapshot (that is a future #184 concern, in its own, differently named tables). One row per logical
 * window ({@code UNIQUE(window_kind, local_start_epoch_day, zone_id, week_start)}); a later capture of
 * the same window either {@link #save replaces} it (the body genuinely changed) or leaves it untouched
 * (an idempotent resend of the identical body). Metrics live in a child table so each stays
 * individually typed and queryable rather than packed into a blob.
 *
 * <p>{@code revision} is assigned here, not by the caller: the first capture of a window is always
 * revision 1, and each later capture whose body actually differs is {@code existingRevision + 1}. This
 * is deliberately <em>not</em> derived from any timestamp (an earlier version derived it from
 * {@code cutoff}'s epoch second, which let two real, distinct captures of the same window within the
 * same wall-clock second collide on one revision number — a signed update built from the second capture
 * could then be rejected by a receiver as a stale revision of the first).
 *
 * <p>The read-compare-write that assigns a revision runs inside {@link #REVISION_LOCK} (#183 review
 * fix), the same in-process-mutual-exclusion pattern {@code ContactService.PERMISSION_LOCK} already
 * established for CodeFit's single-writer model: two threads opening separate connections could
 * otherwise both read the same existing revision before either commits (SQLite's own write-lock only
 * serializes the commits themselves, not this method's read), each independently compute
 * {@code existing + 1}, and the loser's commit would then silently overwrite the winner's content
 * under a revision number that was never actually unique to it. One coarse, table-wide lock (rather
 * than a lock per window) matches {@code PERMISSION_LOCK}'s own granularity and this app's single-user,
 * low-concurrency reality.
 */
public class LocalProgressSnapshotRepository {

    /** Serializes every read-compare-write revision assignment in this class; see the class Javadoc. */
    private static final Object REVISION_LOCK = new Object();

    /** What {@link #save} did with a captured snapshot. */
    public enum SaveOutcome {
        /** No snapshot existed yet for this window; this one is now recorded as revision 1. */
        RECORDED_FIRST,
        /** A snapshot already existed for this window with a different body; it was replaced. */
        REPLACED,
        /** A snapshot already existed for this window with the identical body; nothing changed. */
        UNCHANGED
    }

    /** The outcome of {@link #save}, and the snapshot as actually persisted (with its real, assigned revision). */
    public record SaveResult(SaveOutcome outcome, LocalProgressSnapshot snapshot) {
    }

    /**
     * Computes and assigns the revision for a freshly captured {@code (window, cutoff, metrics)} and
     * persists it. The caller supplies no revision at all — only this repository ever decides one.
     */
    public SaveResult save(ComparisonWindow window, Instant cutoff, Instant capturedAt, List<MetricValue> metrics) {
        synchronized (REVISION_LOCK) {
            try (Connection connection = DatabaseConfig.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    SaveResult result = save(connection, window, cutoff, capturedAt, metrics);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to save local progress snapshot", exception);
            }
        }
    }

    private SaveResult save(Connection connection, ComparisonWindow window, Instant cutoff, Instant capturedAt,
                             List<MetricValue> metrics) throws SQLException {
        Optional<Long> existingId = findRowId(connection, window);
        if (existingId.isPresent()) {
            LocalProgressSnapshot existing = load(connection, existingId.get(), window);
            if (existing.cutoff().equals(cutoff) && sameMetrics(existing.metrics(), metrics)) {
                return new SaveResult(SaveOutcome.UNCHANGED, existing);
            }
            long newRevision = existing.revision() + 1;
            deleteRow(connection, existingId.get());
            LocalProgressSnapshot persisted = new LocalProgressSnapshot(window, cutoff, newRevision, capturedAt, metrics);
            long id = insertSnapshotRow(connection, persisted);
            insertMetrics(connection, id, persisted.metrics());
            return new SaveResult(SaveOutcome.REPLACED, persisted);
        }
        LocalProgressSnapshot persisted = new LocalProgressSnapshot(window, cutoff, 1, capturedAt, metrics);
        long id = insertSnapshotRow(connection, persisted);
        insertMetrics(connection, id, persisted.metrics());
        return new SaveResult(SaveOutcome.RECORDED_FIRST, persisted);
    }

    private static boolean sameMetrics(List<MetricValue> a, List<MetricValue> b) {
        return canonicalOrder(a).equals(canonicalOrder(b));
    }

    private static List<MetricValue> canonicalOrder(List<MetricValue> metrics) {
        List<MetricValue> sorted = new ArrayList<>(metrics);
        sorted.sort(Comparator.comparing(MetricValue::metricId).thenComparingInt(MetricValue::metricVersion));
        return sorted;
    }

    public Optional<LocalProgressSnapshot> findByWindow(ComparisonWindow window) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            Optional<Long> id = findRowId(connection, window);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(load(connection, id.get(), window));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load local progress snapshot", exception);
        }
    }

    private Optional<Long> findRowId(Connection connection, ComparisonWindow window) throws SQLException {
        String sql = "SELECT id FROM local_progress_snapshots WHERE window_kind = ? AND local_start_epoch_day = ? "
                + "AND zone_id = ? AND (week_start = ? OR (week_start IS NULL AND ? IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, window.kind().name());
            statement.setLong(2, window.localStartDate().toEpochDay());
            statement.setString(3, window.zoneId());
            Integer weekStart = window.weekStart() == null ? null : window.weekStart().getValue();
            setNullableInt(statement, 4, weekStart);
            setNullableInt(statement, 5, weekStart);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong("id")) : Optional.empty();
            }
        }
    }

    /**
     * Deletes the snapshot row and its metric children explicitly, rather than relying on the schema's
     * {@code ON DELETE CASCADE} alone: SQLite only enforces foreign keys when a connection has run
     * {@code PRAGMA foreign_keys = ON}, which {@code DatabaseConfig.getConnection()} does not do for
     * every connection it hands out, so a cascade cannot be assumed here.
     */
    private void deleteRow(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM local_progress_snapshot_metrics WHERE snapshot_id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM local_progress_snapshots WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private long insertSnapshotRow(Connection connection, LocalProgressSnapshot snapshot) throws SQLException {
        ComparisonWindow window = snapshot.window();
        String sql = "INSERT INTO local_progress_snapshots (window_kind, local_start_epoch_day, zone_id, week_start, "
                + "window_start, window_end, cutoff, revision, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, window.kind().name());
            statement.setLong(2, window.localStartDate().toEpochDay());
            statement.setString(3, window.zoneId());
            setNullableInt(statement, 4, window.weekStart() == null ? null : window.weekStart().getValue());
            statement.setString(5, window.start().toString());
            statement.setString(6, window.end().toString());
            statement.setString(7, snapshot.cutoff().toString());
            statement.setLong(8, snapshot.revision());
            statement.setString(9, snapshot.capturedAt().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertMetrics(Connection connection, long snapshotId, List<MetricValue> metrics) throws SQLException {
        String sql = "INSERT INTO local_progress_snapshot_metrics (snapshot_id, metric_order, metric_id, "
                + "metric_version, unit, availability, value, sample_size, provenance, timestamp_basis) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int order = 0;
            for (MetricValue metric : metrics) {
                statement.setLong(1, snapshotId);
                statement.setInt(2, order++);
                statement.setString(3, metric.metricId());
                statement.setInt(4, metric.metricVersion());
                statement.setString(5, metric.unit().name());
                statement.setString(6, metric.availability().name());
                statement.setLong(7, metric.value());
                statement.setLong(8, metric.sampleSize());
                statement.setString(9, metric.provenance().name());
                statement.setString(10, metric.timestampBasis().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private LocalProgressSnapshot load(Connection connection, long id, ComparisonWindow window) throws SQLException {
        Instant cutoff;
        long revision;
        Instant capturedAt;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT cutoff, revision, captured_at FROM local_progress_snapshots WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                cutoff = Instant.parse(resultSet.getString("cutoff"));
                revision = resultSet.getLong("revision");
                capturedAt = Instant.parse(resultSet.getString("captured_at"));
            }
        }
        List<MetricValue> metrics = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT metric_id, metric_version, unit, availability, value, sample_size, provenance, timestamp_basis "
                        + "FROM local_progress_snapshot_metrics WHERE snapshot_id = ? ORDER BY metric_order")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    metrics.add(new MetricValue(
                            resultSet.getString("metric_id"),
                            resultSet.getInt("metric_version"),
                            MetricUnit.valueOf(resultSet.getString("unit")),
                            MetricAvailability.valueOf(resultSet.getString("availability")),
                            resultSet.getLong("value"),
                            resultSet.getLong("sample_size"),
                            MetricProvenance.valueOf(resultSet.getString("provenance")),
                            TimestampBasis.valueOf(resultSet.getString("timestamp_basis"))));
                }
            }
        }
        return new LocalProgressSnapshot(window, cutoff, revision, capturedAt, metrics);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
