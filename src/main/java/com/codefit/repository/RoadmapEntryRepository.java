package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link RoadmapEntry} roadmap memberships, separate from {@link ProblemRepository} so a
 * problem's identity never has to be duplicated to give it a second roadmap position (#142).
 *
 * <p>Like {@link ProblemRepository}, every operation has a {@link Connection}-scoped overload so the
 * workbook importer (#143) can run every row inside one shared transaction.
 */
public class RoadmapEntryRepository {

    /** All entries in blind roadmap order: stage order (A..D3), then position within the stage. */
    public List<RoadmapEntry> findAllInRoadmapOrder() {
        String sql = "SELECT * FROM roadmap_entries ORDER BY stage, sequence_order";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return sortByStageOrdinal(mapAll(resultSet));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entries", exception);
        }
    }

    public List<RoadmapEntry> findByStage(RoadmapStage stage) {
        String sql = "SELECT * FROM roadmap_entries WHERE stage = ? ORDER BY sequence_order";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stage.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entries for stage", exception);
        }
    }

    public List<RoadmapEntry> findByProblemId(long problemId) {
        String sql = "SELECT * FROM roadmap_entries WHERE problem_id = ? ORDER BY stage, sequence_order";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return sortByStageOrdinal(mapAll(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entries for problem", exception);
        }
    }

    public Optional<RoadmapEntry> findByStageAndSequence(RoadmapStage stage, int sequenceOrder) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByStageAndSequence(connection, stage, sequenceOrder);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entry", exception);
        }
    }

    public Optional<RoadmapEntry> findByStageAndSequence(Connection connection, RoadmapStage stage, int sequenceOrder) throws SQLException {
        String sql = "SELECT * FROM roadmap_entries WHERE stage = ? AND sequence_order = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stage.name());
            statement.setInt(2, sequenceOrder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<RoadmapEntry> findByProblemIdAndStage(long problemId, RoadmapStage stage) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findByProblemIdAndStage(connection, problemId, stage);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entry", exception);
        }
    }

    public Optional<RoadmapEntry> findByProblemIdAndStage(Connection connection, long problemId, RoadmapStage stage) throws SQLException {
        String sql = "SELECT * FROM roadmap_entries WHERE problem_id = ? AND stage = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            statement.setString(2, stage.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
            }
        }
    }

    public RoadmapEntry save(RoadmapEntry entry) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, entry);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save roadmap entry", exception);
        }
    }

    public RoadmapEntry save(Connection connection, RoadmapEntry entry) throws SQLException {
        String sql = "INSERT INTO roadmap_entries (problem_id, stage, sequence_order, set_number, mandatory, suggested_level) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, entry.getProblemId());
            statement.setString(2, entry.getStage().name());
            statement.setInt(3, entry.getSequenceOrder());
            if (entry.getSetNumber() == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, entry.getSetNumber());
            }
            statement.setInt(5, entry.isMandatory() ? 1 : 0);
            statement.setString(6, entry.getSuggestedLevel() == null ? null : entry.getSuggestedLevel().name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    entry.setId(keys.getLong(1));
                }
            }
        }
        return entry;
    }

    public void update(RoadmapEntry entry) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            update(connection, entry);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update roadmap entry", exception);
        }
    }

    public void update(Connection connection, RoadmapEntry entry) throws SQLException {
        String sql = "UPDATE roadmap_entries SET sequence_order = ?, set_number = ?, mandatory = ?, suggested_level = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, entry.getSequenceOrder());
            if (entry.getSetNumber() == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setInt(2, entry.getSetNumber());
            }
            statement.setInt(3, entry.isMandatory() ? 1 : 0);
            statement.setString(4, entry.getSuggestedLevel() == null ? null : entry.getSuggestedLevel().name());
            statement.setLong(5, entry.getId());
            statement.executeUpdate();
        }
    }

    private List<RoadmapEntry> sortByStageOrdinal(List<RoadmapEntry> entries) {
        return entries.stream()
                .sorted((a, b) -> {
                    int stageCompare = Integer.compare(a.getStage().ordinal(), b.getStage().ordinal());
                    return stageCompare != 0 ? stageCompare : Integer.compare(a.getSequenceOrder(), b.getSequenceOrder());
                })
                .toList();
    }

    private List<RoadmapEntry> mapAll(ResultSet resultSet) throws SQLException {
        List<RoadmapEntry> entries = new ArrayList<>();
        while (resultSet.next()) {
            entries.add(mapEntry(resultSet));
        }
        return entries;
    }

    /** Every membership created or last touched by one import batch (#149) — used to preview or
     *  confirm what {@link #deleteByImportBatchId} would remove. */
    public List<RoadmapEntry> findByImportBatchId(long importBatchId) {
        String sql = "SELECT * FROM roadmap_entries WHERE import_batch_id = ? ORDER BY stage, sequence_order";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, importBatchId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return sortByStageOrdinal(mapAll(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entries for import batch", exception);
        }
    }

    /** Stamps which import batch created or last touched this entry (#149); a plain column with no
     *  foreign key, so it never affects deletion of anything else. */
    public void updateImportBatchId(Connection connection, long entryId, long importBatchId) throws SQLException {
        String sql = "UPDATE roadmap_entries SET import_batch_id = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, importBatchId);
            statement.setLong(2, entryId);
            statement.executeUpdate();
        }
    }

    /**
     * Deletes every roadmap membership belonging to one import batch — and nothing else.
     * {@code problem_progress}/{@code problem_attempts}/{@code flashcards} never reference
     * {@code roadmap_entries}, only {@code problems} directly, so this can never touch a learner's
     * progress, attempt history, or flashcards; the underlying {@link com.codefit.model.Problem} rows
     * are left in place too, since another batch (or a manual addition) may still reference them.
     */
    public int deleteByImportBatchId(Connection connection, long importBatchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM roadmap_entries WHERE import_batch_id = ?")) {
            statement.setLong(1, importBatchId);
            return statement.executeUpdate();
        }
    }

    private RoadmapEntry mapEntry(ResultSet resultSet) throws SQLException {
        String suggestedLevel = resultSet.getString("suggested_level");
        RoadmapEntry entry = new RoadmapEntry(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                RoadmapStage.valueOf(resultSet.getString("stage")),
                resultSet.getInt("sequence_order"),
                nullableInteger(resultSet, "set_number"),
                resultSet.getInt("mandatory") == 1,
                suggestedLevel == null ? null : DifficultyLevel.valueOf(suggestedLevel),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T'))
        );
        entry.setImportBatchId(nullableLong(resultSet, "import_batch_id"));
        return entry;
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
