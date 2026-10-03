package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.protocol.WindowKind;
import com.codefit.peer.sync.PeerProgressSummary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The validated peer inbox for {@code PROGRESS_SUMMARY} (#184). Holds only the latest accepted
 * revision per {@code (author, objectId)} - supersession, never an append log - completely separate
 * from this device's own {@code local_progress_snapshots} (#183): nothing here is this learner's own
 * evidence, and nothing in this table is ever read by {@code ProgressSnapshotService}/mastery/XP.
 */
public class PeerProgressSummaryRepository {

    public void upsert(Connection connection, IdentityId author, ObjectId objectId, long epoch, long revision,
                        ProgressSummary body, Instant receivedAt) throws SQLException {
        long summaryId;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM peer_progress_summaries WHERE author_identity_id = ? AND object_id = ?")) {
            delete.setBytes(1, author.bytes());
            delete.setBytes(2, objectId.bytes());
            delete.executeUpdate();
        }
        ComparisonWindow window = body.window();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO peer_progress_summaries (author_identity_id, object_id, epoch, revision, window_kind, "
                        + "local_start_epoch_day, zone_id, week_start, window_start, window_end, cutoff, received_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setBytes(1, author.bytes());
            insert.setBytes(2, objectId.bytes());
            insert.setLong(3, epoch);
            insert.setLong(4, revision);
            insert.setString(5, window.kind().name());
            insert.setLong(6, window.localStartDate().toEpochDay());
            insert.setString(7, window.zoneId());
            if (window.weekStart() == null) {
                insert.setNull(8, java.sql.Types.INTEGER);
            } else {
                insert.setInt(8, window.weekStart().getValue());
            }
            insert.setString(9, window.start().toString());
            insert.setString(10, window.end().toString());
            insert.setString(11, body.cutoff().toString());
            insert.setString(12, receivedAt.toString());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                summaryId = keys.getLong(1);
            }
        }
        try (PreparedStatement insertMetric = connection.prepareStatement(
                "INSERT INTO peer_progress_summary_metrics (summary_id, metric_order, metric_id, metric_version, unit, "
                        + "availability, value, sample_size, provenance, timestamp_basis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            List<MetricValue> metrics = body.metrics();
            for (int i = 0; i < metrics.size(); i++) {
                MetricValue metric = metrics.get(i);
                insertMetric.setLong(1, summaryId);
                insertMetric.setInt(2, i);
                insertMetric.setString(3, metric.metricId());
                insertMetric.setInt(4, metric.metricVersion());
                insertMetric.setString(5, metric.unit().name());
                insertMetric.setString(6, metric.availability().name());
                insertMetric.setLong(7, metric.value());
                insertMetric.setLong(8, metric.sampleSize());
                insertMetric.setString(9, metric.provenance().name());
                insertMetric.setString(10, metric.timestampBasis().name());
                insertMetric.executeUpdate();
            }
        }
    }

    /** Deletes the cached copy for this object, if any - a tombstone's cache-deletion effect. */
    public void delete(Connection connection, IdentityId author, ObjectId objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM peer_progress_summaries WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            statement.executeUpdate();
        }
    }

    public List<PeerProgressSummary> findByAuthor(IdentityId author) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM peer_progress_summaries WHERE author_identity_id = ? ORDER BY window_start")) {
            statement.setBytes(1, author.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PeerProgressSummary> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(map(connection, resultSet));
                }
                return results;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load peer progress summaries", exception);
        }
    }

    public Optional<PeerProgressSummary> findByAuthorAndObjectId(IdentityId author, ObjectId objectId) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM peer_progress_summaries WHERE author_identity_id = ? AND object_id = ?")) {
            statement.setBytes(1, author.bytes());
            statement.setBytes(2, objectId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(connection, resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load peer progress summary", exception);
        }
    }

    private PeerProgressSummary map(Connection connection, ResultSet resultSet) throws SQLException {
        long summaryId = resultSet.getLong("id");
        ComparisonWindow window = new ComparisonWindow(
                WindowKind.valueOf(resultSet.getString("window_kind")),
                LocalDate.ofEpochDay(resultSet.getLong("local_start_epoch_day")),
                resultSet.getString("zone_id"),
                resultSet.getObject("week_start") == null ? null : DayOfWeek.of(resultSet.getInt("week_start")),
                Instant.parse(resultSet.getString("window_start")),
                Instant.parse(resultSet.getString("window_end")));
        List<MetricValue> metrics = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM peer_progress_summary_metrics WHERE summary_id = ? ORDER BY metric_order")) {
            statement.setLong(1, summaryId);
            try (ResultSet metricRows = statement.executeQuery()) {
                while (metricRows.next()) {
                    metrics.add(new MetricValue(
                            metricRows.getString("metric_id"),
                            metricRows.getInt("metric_version"),
                            MetricUnit.valueOf(metricRows.getString("unit")),
                            MetricAvailability.valueOf(metricRows.getString("availability")),
                            metricRows.getLong("value"),
                            metricRows.getLong("sample_size"),
                            MetricProvenance.valueOf(metricRows.getString("provenance")),
                            TimestampBasis.valueOf(metricRows.getString("timestamp_basis"))));
                }
            }
        }
        return new PeerProgressSummary(new IdentityId(resultSet.getBytes("author_identity_id")),
                new ObjectId(resultSet.getBytes("object_id")), resultSet.getLong("epoch"), resultSet.getLong("revision"),
                window, Instant.parse(resultSet.getString("cutoff")), metrics,
                Instant.parse(resultSet.getString("received_at")));
    }
}
