package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.GuidanceSource;
import com.codefit.model.ProblemGuidance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Persists each {@link com.codefit.model.Problem}'s single {@link ProblemGuidance} row
 * ({@code UNIQUE(problem_id)}, #162) — the same one-row-per-problem shape as
 * {@link ProblemProgressRepository}, kept in its own table since guidance content and learner
 * progress are entirely independent concerns.
 */
public class ProblemGuidanceRepository {

    public Optional<ProblemGuidance> findByProblemId(long problemId) {
        String sql = "SELECT * FROM problem_guidance WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapGuidance(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem guidance", exception);
        }
    }

    public ProblemGuidance save(ProblemGuidance guidance) {
        String sql = "INSERT INTO problem_guidance (problem_id, source, clarify_text, observation_text, "
                + "approach_text, explanation_text, pseudocode_text, complexity_notes, common_mistakes_text, "
                + "prerequisites, reference_links) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindFields(statement, guidance);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    guidance.setId(keys.getLong(1));
                }
            }
            return findByProblemId(guidance.getProblemId()).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem guidance", exception);
        }
    }

    public void update(ProblemGuidance guidance) {
        String sql = "UPDATE problem_guidance SET source = ?, clarify_text = ?, observation_text = ?, "
                + "approach_text = ?, explanation_text = ?, pseudocode_text = ?, complexity_notes = ?, "
                + "common_mistakes_text = ?, prerequisites = ?, reference_links = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guidance.getSource().name());
            statement.setString(2, guidance.getClarifyText());
            statement.setString(3, guidance.getObservationText());
            statement.setString(4, guidance.getApproachText());
            statement.setString(5, guidance.getExplanationText());
            statement.setString(6, guidance.getPseudocodeText());
            statement.setString(7, guidance.getComplexityNotes());
            statement.setString(8, guidance.getCommonMistakesText());
            statement.setString(9, guidance.getPrerequisitesEncoded());
            statement.setString(10, guidance.getReferenceLinksEncoded());
            statement.setLong(11, guidance.getProblemId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem guidance", exception);
        }
    }

    private void bindFields(PreparedStatement statement, ProblemGuidance guidance) throws SQLException {
        statement.setLong(1, guidance.getProblemId());
        statement.setString(2, guidance.getSource().name());
        statement.setString(3, guidance.getClarifyText());
        statement.setString(4, guidance.getObservationText());
        statement.setString(5, guidance.getApproachText());
        statement.setString(6, guidance.getExplanationText());
        statement.setString(7, guidance.getPseudocodeText());
        statement.setString(8, guidance.getComplexityNotes());
        statement.setString(9, guidance.getCommonMistakesText());
        statement.setString(10, guidance.getPrerequisitesEncoded());
        statement.setString(11, guidance.getReferenceLinksEncoded());
    }

    private ProblemGuidance mapGuidance(ResultSet resultSet) throws SQLException {
        return new ProblemGuidance(
                resultSet.getLong("id"),
                resultSet.getLong("problem_id"),
                GuidanceSource.valueOf(resultSet.getString("source")),
                resultSet.getString("clarify_text"),
                resultSet.getString("observation_text"),
                resultSet.getString("approach_text"),
                resultSet.getString("explanation_text"),
                resultSet.getString("pseudocode_text"),
                resultSet.getString("complexity_notes"),
                resultSet.getString("common_mistakes_text"),
                resultSet.getString("prerequisites"),
                resultSet.getString("reference_links"),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T')),
                LocalDateTime.parse(resultSet.getString("updated_at").replace(' ', 'T'))
        );
    }
}
