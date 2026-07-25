package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.AssessmentItem;
import com.codefit.model.AssessmentVariant;
import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists {@link AssessmentItem}s and their {@link AssessmentVariant}s in their own tables,
 * entirely separate from {@link FlashcardRepository}, so the assessment bank can never be confused
 * with (or accidentally joined against) normal review cards (#104).
 */
public class AssessmentItemRepository {

    public List<AssessmentItem> findAll() {
        String sql = """
                SELECT ai.id AS item_id, ai.skill_category, ai.module_name, ai.card_type, ai.validation_mode, ai.created_at,
                       av.id AS variant_id, av.variant_index, av.scenario, av.accepted_answers, av.reference_answer,
                       av.simulated_output, av.hint
                FROM assessment_items ai
                JOIN assessment_variants av ON av.assessment_item_id = ai.id
                ORDER BY ai.id, av.variant_index
                """;
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapItems(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load assessment items", exception);
        }
    }

    public Optional<AssessmentItem> findById(long id) {
        return findAll().stream().filter(item -> item.getId() == id).findFirst();
    }

    /** Saves a new item together with all of its variants in a single transaction. */
    public AssessmentItem save(String skillCategory, String moduleName, CardType cardType, ValidationMode validationMode,
                               List<VariantInput> variants) {
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("An assessment item needs at least one variant.");
        }
        String insertItemSql = "INSERT INTO assessment_items (skill_category, module_name, card_type, validation_mode) VALUES (?, ?, ?, ?)";
        String insertVariantSql = "INSERT INTO assessment_variants (assessment_item_id, variant_index, scenario, accepted_answers, reference_answer, simulated_output, hint) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseConfig.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long itemId;
                try (PreparedStatement statement = connection.prepareStatement(insertItemSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, skillCategory);
                    statement.setString(2, moduleName);
                    statement.setString(3, cardType.name());
                    statement.setString(4, validationMode.name());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        itemId = keys.getLong(1);
                    }
                }
                for (int index = 0; index < variants.size(); index++) {
                    VariantInput variant = variants.get(index);
                    try (PreparedStatement statement = connection.prepareStatement(insertVariantSql)) {
                        statement.setLong(1, itemId);
                        statement.setInt(2, index);
                        statement.setString(3, variant.scenario());
                        statement.setString(4, variant.acceptedAnswers());
                        statement.setString(5, variant.referenceAnswer());
                        statement.setString(6, variant.simulatedOutput());
                        statement.setString(7, variant.hint());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return findById(itemId).orElseThrow();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save assessment item", exception);
        }
    }

    private List<AssessmentItem> mapItems(ResultSet resultSet) throws SQLException {
        Map<Long, ItemRow> rowsById = new LinkedHashMap<>();
        Map<Long, List<AssessmentVariant>> variantsByItem = new LinkedHashMap<>();
        while (resultSet.next()) {
            long itemId = resultSet.getLong("item_id");
            rowsById.putIfAbsent(itemId, new ItemRow(
                    resultSet.getString("skill_category"),
                    resultSet.getString("module_name"),
                    enumValue(CardType.class, resultSet.getString("card_type"), CardType.CONCEPT),
                    enumValue(ValidationMode.class, resultSet.getString("validation_mode"), ValidationMode.CASE_INSENSITIVE),
                    LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T'))));
            variantsByItem.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(new AssessmentVariant(
                    resultSet.getLong("variant_id"),
                    resultSet.getInt("variant_index"),
                    resultSet.getString("scenario"),
                    resultSet.getString("accepted_answers"),
                    resultSet.getString("reference_answer"),
                    resultSet.getString("simulated_output"),
                    resultSet.getString("hint")));
        }
        List<AssessmentItem> items = new ArrayList<>();
        for (Map.Entry<Long, ItemRow> entry : rowsById.entrySet()) {
            ItemRow row = entry.getValue();
            items.add(new AssessmentItem(entry.getKey(), row.skillCategory, row.moduleName, row.cardType,
                    row.validationMode, variantsByItem.get(entry.getKey()), row.createdAt));
        }
        return items;
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumClass, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private record ItemRow(String skillCategory, String moduleName, CardType cardType,
                           ValidationMode validationMode, LocalDateTime createdAt) {
    }

    public record VariantInput(String scenario, String acceptedAnswers, String referenceAnswer,
                               String simulatedOutput, String hint) {
    }
}
