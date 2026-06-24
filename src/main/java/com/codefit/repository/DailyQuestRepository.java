package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DailyQuest;
import com.codefit.model.DailyQuestObjectiveType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;

public class DailyQuestRepository {
    public Optional<DailyQuest> findByDate(LocalDate questDate) {
        String sql = "SELECT * FROM daily_quests WHERE quest_date = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, questDate.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapQuest(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load daily quest", exception);
        }
    }

    public DailyQuest save(DailyQuest quest) {
        String sql = """
                INSERT INTO daily_quests (quest_date, objective_type, skill_category, target_count, current_count, completed, xp_awarded, xp_reward)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindQuest(statement, quest);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    quest.setId(keys.getLong(1));
                }
            }
            return quest;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save daily quest", exception);
        }
    }

    public void update(DailyQuest quest) {
        String sql = """
                UPDATE daily_quests
                SET quest_date = ?, objective_type = ?, skill_category = ?, target_count = ?, current_count = ?, completed = ?, xp_awarded = ?, xp_reward = ?
                WHERE id = ?
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQuest(statement, quest);
            statement.setLong(9, quest.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update daily quest", exception);
        }
    }

    private void bindQuest(PreparedStatement statement, DailyQuest quest) throws SQLException {
        statement.setString(1, quest.getQuestDate().toString());
        statement.setString(2, quest.getObjectiveType().name());
        statement.setString(3, quest.getSkillCategory());
        statement.setInt(4, quest.getTargetCount());
        statement.setInt(5, quest.getCurrentCount());
        statement.setInt(6, quest.isCompleted() ? 1 : 0);
        statement.setInt(7, quest.isXpAwarded() ? 1 : 0);
        statement.setInt(8, quest.getXpReward());
    }

    private DailyQuest mapQuest(ResultSet resultSet) throws SQLException {
        return new DailyQuest(
                resultSet.getLong("id"),
                LocalDate.parse(resultSet.getString("quest_date")),
                DailyQuestObjectiveType.valueOf(resultSet.getString("objective_type")),
                resultSet.getString("skill_category"),
                resultSet.getInt("target_count"),
                resultSet.getInt("current_count"),
                resultSet.getInt("completed") == 1,
                resultSet.getInt("xp_awarded") == 1,
                resultSet.getInt("xp_reward")
        );
    }
}
