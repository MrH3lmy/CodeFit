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
        String sql = "SELECT * FROM roadmap_entries WHERE stage = ? AND sequence_order = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stage.name());
            statement.setInt(2, sequenceOrder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entry", exception);
        }
    }

    public Optional<RoadmapEntry> findByProblemIdAndStage(long problemId, RoadmapStage stage) {
        String sql = "SELECT * FROM roadmap_entries WHERE problem_id = ? AND stage = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            statement.setString(2, stage.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load roadmap entry", exception);
        }
    }

    public RoadmapEntry save(RoadmapEntry entry) {
        String sql = "INSERT INTO roadmap_entries (problem_id, stage, sequence_order, set_number, mandatory, suggested_level) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            return entry;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save roadmap entry", exception);
        }
    }

    public void update(RoadmapEntry entry) {
        String sql = "UPDATE roadmap_entries SET sequence_order = ?, set_number = ?, mandatory = ?, suggested_level = ? WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
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
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update roadmap entry", exception);
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

    private RoadmapEntry mapEntry(ResultSet resultSet) throws SQLException {
        String suggestedLevel = resultSet.getString("suggested_level");
        return new RoadmapEntry(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                RoadmapStage.valueOf(resultSet.getString("stage")),
                resultSet.getInt("sequence_order"),
                nullableInteger(resultSet, "set_number"),
                resultSet.getInt("mandatory") == 1,
                suggestedLevel == null ? null : DifficultyLevel.valueOf(suggestedLevel),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T'))
        );
    }

    private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
