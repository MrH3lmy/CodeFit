package com.codefit.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A transfer-assessment item: measures whether a learner can apply a concept in an unseen or
 * parameterized scenario, rather than recognize the familiar wording of a normal review card. Stored
 * entirely separately from {@link Flashcard} (its own tables, its own repository) so assessment
 * content is never mixed into normal review and never seen by the learner before an assessment
 * (#104). Grading still reuses the existing per-{@link CardType} validators (see
 * {@code AssessmentGradingService}) rather than inventing a second grading engine.
 */
public class AssessmentItem {
    private final long id;
    private final String skillCategory;
    private final String moduleName;
    private final CardType cardType;
    private final ValidationMode validationMode;
    private final List<AssessmentVariant> variants;
    private final LocalDateTime createdAt;

    public AssessmentItem(long id, String skillCategory, String moduleName, CardType cardType,
                          ValidationMode validationMode, List<AssessmentVariant> variants, LocalDateTime createdAt) {
        this.id = id;
        this.skillCategory = normalizeSkillCategory(skillCategory);
        this.moduleName = moduleName == null || moduleName.isBlank() ? "General" : moduleName.strip();
        this.cardType = cardType == null ? CardType.CONCEPT : cardType;
        this.validationMode = validationMode == null ? ValidationMode.CASE_INSENSITIVE : validationMode;
        this.variants = variants == null ? List.of() : List.copyOf(variants);
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getSkillCategory() { return skillCategory; }
    public String getModuleName() { return moduleName; }
    public CardType getCardType() { return cardType; }
    public ValidationMode getValidationMode() { return validationMode; }
    public List<AssessmentVariant> getVariants() { return variants; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Rotates deterministically through this item's variants based on how many times it has
     * already been attempted, so repeated weekly assessments serve a different scenario each time
     * instead of the exact same wording (#104), wrapping back to the first variant once all have
     * been shown.
     */
    public AssessmentVariant variantForAttemptCount(int previousAttemptCount) {
        if (variants.isEmpty()) {
            throw new IllegalStateException("Assessment item " + id + " has no variants configured.");
        }
        return variants.get(Math.floorMod(previousAttemptCount, variants.size()));
    }

    private static String normalizeSkillCategory(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }
}
