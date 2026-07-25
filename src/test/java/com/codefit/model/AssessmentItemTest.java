package com.codefit.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessmentItemTest {

    private AssessmentItem itemWithVariants(int variantCount) {
        List<AssessmentVariant> variants = java.util.stream.IntStream.range(0, variantCount)
                .mapToObj(index -> new AssessmentVariant(index, index, "scenario " + index, null, "reference " + index, null, null))
                .toList();
        return new AssessmentItem(1, "SQL", "Module 1", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE, variants, LocalDateTime.now());
    }

    @Test
    void rotatesThroughEveryVariantThenWrapsAround() {
        AssessmentItem item = itemWithVariants(3);
        assertEquals(0, item.variantForAttemptCount(0).variantIndex());
        assertEquals(1, item.variantForAttemptCount(1).variantIndex());
        assertEquals(2, item.variantForAttemptCount(2).variantIndex());
        assertEquals(0, item.variantForAttemptCount(3).variantIndex());
        assertEquals(1, item.variantForAttemptCount(4).variantIndex());
    }

    @Test
    void aSingleVariantItemAlwaysServesThatVariant() {
        AssessmentItem item = itemWithVariants(1);
        assertEquals(0, item.variantForAttemptCount(0).variantIndex());
        assertEquals(0, item.variantForAttemptCount(50).variantIndex());
    }

    @Test
    void anItemWithNoVariantsCannotBeServed() {
        AssessmentItem item = new AssessmentItem(1, "SQL", "Module 1", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE, List.of(), LocalDateTime.now());
        assertThrows(IllegalStateException.class, () -> item.variantForAttemptCount(0));
    }

    @Test
    void blankSkillCategoryAndModuleNameNormalizeToDefaults() {
        AssessmentItem item = new AssessmentItem(1, "  ", " ", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                List.of(new AssessmentVariant(1, 0, "scenario", null, "reference", null, null)), LocalDateTime.now());
        assertEquals("General", item.getSkillCategory());
        assertEquals("General", item.getModuleName());
    }
}
