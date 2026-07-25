package com.codefit.service;

import com.codefit.model.AssessmentItem;
import com.codefit.model.AssessmentVariant;
import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;
import com.codefit.service.WeeklyAssessmentSelectionService.SelectedAssessment;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyAssessmentSelectionServiceTest {

    private AssessmentItem item(long id, String skillCategory, String moduleName) {
        List<AssessmentVariant> variants = List.of(
                new AssessmentVariant(id * 10 + 1, 0, "scenario A for " + skillCategory, null, "reference A", null, null),
                new AssessmentVariant(id * 10 + 2, 1, "scenario B for " + skillCategory, null, "reference B", null, null));
        return new AssessmentItem(id, skillCategory, moduleName, CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                variants, LocalDateTime.now());
    }

    @Test
    void selectionCoversEveryModuleBeforeDrainingAnySingleModuleTwice() {
        // Three modules, three items each — a budget of 3 must pick exactly one item per module,
        // never two items from the same module while another module has zero.
        List<AssessmentItem> items = new ArrayList<>();
        for (int module = 1; module <= 3; module++) {
            for (int i = 1; i <= 3; i++) {
                items.add(item(module * 10L + i, "Skill" + module, "Module " + module));
            }
        }

        List<SelectedAssessment> selected = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                items, List.of(), Map.of(), 3);

        assertEquals(3, selected.size());
        Set<String> modulesCovered = new HashSet<>();
        selected.forEach(assessment -> modulesCovered.add(assessment.item().getModuleName()));
        assertEquals(3, modulesCovered.size(), "every module must be represented before any module contributes a second item");
    }

    @Test
    void weakSkillItemsAreDrainedBeforeOtherItemsInTheSameModule() {
        // Two modules, each with two items (one weak-skill item, one non-weak item). With a budget
        // of 2, both items chosen must be the weak-skill ones since they are prioritized within
        // each module's queue.
        AssessmentItem weakModule1 = item(1, "Concurrency", "Module 1");
        AssessmentItem strongModule1 = item(2, "Testing", "Module 1");
        AssessmentItem weakModule2 = item(3, "Security", "Module 2");
        AssessmentItem strongModule2 = item(4, "Deployment", "Module 2");
        List<AssessmentItem> items = List.of(weakModule1, strongModule1, weakModule2, strongModule2);

        List<SelectedAssessment> selected = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                items, List.of("Concurrency", "Security"), Map.of(), 2);

        assertEquals(2, selected.size());
        assertTrue(selected.stream().allMatch(assessment ->
                assessment.item().getSkillCategory().equals("Concurrency") || assessment.item().getSkillCategory().equals("Security")));
    }

    @Test
    void selectionIsCappedAtTheRequestedItemCount() {
        List<AssessmentItem> items = List.of(item(1, "SQL", "Module 1"), item(2, "Kafka", "Module 2"),
                item(3, "Security", "Module 3"));

        List<SelectedAssessment> selected = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                items, List.of(), Map.of(), 2);

        assertEquals(2, selected.size());
    }

    @Test
    void emptyItemPoolProducesNoSelection() {
        assertEquals(List.of(), WeeklyAssessmentSelectionService.selectWeeklyAssessment(List.of(), List.of(), Map.of(), 8));
    }

    @Test
    void variantRotatesByPriorAttemptCountSoARepeatedAssessmentServesADifferentScenario() {
        AssessmentItem itemWithVariants = item(1, "SQL", "Module 1");

        List<SelectedAssessment> firstTime = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                List.of(itemWithVariants), List.of(), Map.of(), 1);
        List<SelectedAssessment> afterOneAttempt = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                List.of(itemWithVariants), List.of(), Map.of(1L, 1), 1);
        List<SelectedAssessment> afterTwoAttempts = WeeklyAssessmentSelectionService.selectWeeklyAssessment(
                List.of(itemWithVariants), List.of(), Map.of(1L, 2), 1);

        assertEquals(0, firstTime.get(0).variant().variantIndex());
        assertEquals(1, afterOneAttempt.get(0).variant().variantIndex());
        assertEquals(0, afterTwoAttempts.get(0).variant().variantIndex(), "rotation wraps back to the first variant");
    }
}
