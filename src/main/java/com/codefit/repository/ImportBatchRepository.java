package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ImportBatch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link ImportBatch} source-attribution records (#149). Every operation has a
 * {@link Connection}-scoped overload so a batch row is created inside the same transaction as the
 * workbook import it describes (see {@code TrainingSheetImportService}), and rolled back with it on
 * a dry-run preview or a failed import.
 */
public class ImportBatchRepository {

    public List<ImportBatch> findAll() {
        String sql = "SELECT * FROM import_batches ORDER BY imported_at DESC";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<ImportBatch> batches = new ArrayList<>();
            while (resultSet.next()) {
                batches.add(mapBatch(resultSet));
            }
            return batches;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load import batches", exception);
        }
    }

    public Optional<ImportBatch> findById(long id) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findById(connection, id);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load import batch", exception);
        }
    }

    public Optional<ImportBatch> findById(Connection connection, long id) throws SQLException {
        String sql = "SELECT * FROM import_batches WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapBatch(resultSet)) : Optional.empty();
            }
        }
    }

    public ImportBatch save(Connection connection, ImportBatch batch) throws SQLException {
        String sql = "INSERT INTO import_batches (source_name, source_url, author, version) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, batch.getSourceName());
            statement.setString(2, batch.getSourceUrl());
            statement.setString(3, batch.getAuthor());
            statement.setString(4, batch.getVersion());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    batch.setId(keys.getLong(1));
                }
            }
        }
        return findById(connection, batch.getId()).orElseThrow();
    }

    /** Deletes the batch row itself; callers delete its {@code roadmap_entries} memberships first
     *  (see {@code RoadmapEntryRepository#deleteByImportBatchId}) since there is no DB-level cascade. */
    public void delete(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM import_batches WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private ImportBatch mapBatch(ResultSet resultSet) throws SQLException {
        return new ImportBatch(
                resultSet.getLong("id"),
                resultSet.getString("source_name"),
                resultSet.getString("source_url"),
                resultSet.getString("author"),
                resultSet.getString("version"),
                LocalDateTime.parse(resultSet.getString("imported_at").replace(' ', 'T')));
    }
}
