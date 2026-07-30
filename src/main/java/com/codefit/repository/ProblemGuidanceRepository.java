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
 * Persists authored guidance separately from learner overrides (#162). The original
 * {@code problem_guidance} table remains the single base-source row for a problem, while
 * {@code problem_guidance_learner_overrides} stores a learner-authored copy without relabeling or
 * destroying CodeFit/imported/provider content. Reads prefer the learner override when one exists.
 */
public class ProblemGuidanceRepository {

    private static final String BASE_TABLE = "problem_guidance";
    private static final String LEARNER_TABLE = "problem_guidance_learner_overrides";

    public Optional<ProblemGuidance> findByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            ensureLearnerOverrideSchema(connection);
            Optional<ProblemGuidance> learner = findInTable(connection, LEARNER_TABLE, problemId, null);
            return learner.isPresent() ? learner : findInTable(connection, BASE_TABLE, problemId, null);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem guidance", exception);
        }
    }

    public Optional<ProblemGuidance> findBaseByProblemId(long problemId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            ensureLearnerOverrideSchema(connection);
            return findInTable(connection, BASE_TABLE, problemId, null);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load base problem guidance", exception);
        }
    }

    public Optional<ProblemGuidance> findByProblemIdAndSource(long problemId, GuidanceSource source) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            ensureLearnerOverrideSchema(connection);
            String table = tableFor(source);
            return findInTable(connection, table, problemId,
                    source == GuidanceSource.LEARNER ? null : source);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load problem guidance by source", exception);
        }
    }

    public ProblemGuidance save(ProblemGuidance guidance) {
        String table = tableFor(guidance.getSource());
        String sql = "INSERT INTO " + table + " (problem_id, source, clarify_text, observation_text, "
                + "approach_text, explanation_text, pseudocode_text, complexity_notes, common_mistakes_text, "
                + "prerequisites, reference_links) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection()) {
            ensureLearnerOverrideSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindFields(statement, guidance);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        guidance.setId(keys.getLong(1));
                    }
                }
            }
            return findInTable(connection, table, guidance.getProblemId(),
                    guidance.getSource() == GuidanceSource.LEARNER ? null : guidance.getSource()).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save problem guidance", exception);
        }
    }

    public void update(ProblemGuidance guidance) {
        String table = tableFor(guidance.getSource());
        String sql = "UPDATE " + table + " SET source = ?, clarify_text = ?, observation_text = ?, "
                + "approach_text = ?, explanation_text = ?, pseudocode_text = ?, complexity_notes = ?, "
                + "common_mistakes_text = ?, prerequisites = ?, reference_links = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE problem_id = ?";
        try (Connection connection = DatabaseConfig.getConnection()) {
            ensureLearnerOverrideSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update problem guidance", exception);
        }
    }

    private Optional<ProblemGuidance> findInTable(Connection connection, String table, long problemId,
                                                  GuidanceSource source) throws SQLException {
        String sql = "SELECT * FROM " + table + " WHERE problem_id = ?"
                + (source == null ? "" : " AND source = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, problemId);
            if (source != null) {
                statement.setString(2, source.name());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapGuidance(resultSet)) : Optional.empty();
            }
        }
    }

    private String tableFor(GuidanceSource source) {
        return source == GuidanceSource.LEARNER ? LEARNER_TABLE : BASE_TABLE;
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

    /**
     * Creates the additive learner-override table and moves legacy learner rows out of the base table.
     * The original row is retained for every non-learner source, so editing in the workspace cannot
     * silently relabel CodeFit/imported/provider text as learner-authored.
     */
    private void ensureLearnerOverrideSchema(Connection connection) throws SQLException {
        ensureColumn(connection, BASE_TABLE, "pseudocode_text", "TEXT");
        ensureColumn(connection, BASE_TABLE, "complexity_notes", "TEXT");
        ensureColumn(connection, BASE_TABLE, "common_mistakes_text", "TEXT");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problem_guidance_learner_overrides (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL UNIQUE,
                        source TEXT NOT NULL DEFAULT 'LEARNER' CHECK (source = 'LEARNER'),
                        clarify_text TEXT,
                        observation_text TEXT,
                        approach_text TEXT,
                        explanation_text TEXT,
                        pseudocode_text TEXT,
                        complexity_notes TEXT,
                        common_mistakes_text TEXT,
                        prerequisites TEXT,
                        reference_links TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO problem_guidance_learner_overrides
                        (problem_id, source, clarify_text, observation_text, approach_text,
                         explanation_text, pseudocode_text, complexity_notes, common_mistakes_text,
                         prerequisites, reference_links, created_at, updated_at)
                    SELECT problem_id, 'LEARNER', clarify_text, observation_text, approach_text,
                           explanation_text, pseudocode_text, complexity_notes, common_mistakes_text,
                           prerequisites, reference_links, created_at, updated_at
                    FROM problem_guidance
                    WHERE source = 'LEARNER'
                    """);
            statement.executeUpdate("""
                    DELETE FROM problem_guidance
                    WHERE source = 'LEARNER'
                      AND problem_id IN (SELECT problem_id FROM problem_guidance_learner_overrides)
                    """);
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
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
