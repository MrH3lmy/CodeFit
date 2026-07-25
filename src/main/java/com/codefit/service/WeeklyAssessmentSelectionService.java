package com.codefit.service;

import com.codefit.model.AssessmentItem;
import com.codefit.model.AssessmentVariant;
import com.codefit.repository.AssessmentAttemptRepository;
import com.codefit.repository.AssessmentItemRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a weekly transfer assessment: a small set of {@link AssessmentItem}s that prioritizes
 * skills currently flagged as needing practice (reusing {@link StatsService#getNeedsPracticeSkills()}
 * rather than inventing a second weakness metric) while still covering multiple modules, each served
 * with whichever variant hasn't been shown most recently (#104).
 */
public class WeeklyAssessmentSelectionService {

    public static final int DEFAULT_ITEM_COUNT = 8;

    private final AssessmentItemRepository assessmentItemRepository = new AssessmentItemRepository();
    private final AssessmentAttemptRepository assessmentAttemptRepository = new AssessmentAttemptRepository();
    private final StatsService statsService = new StatsService();

    public List<SelectedAssessment> selectWeeklyAssessment() {
        return selectWeeklyAssessment(DEFAULT_ITEM_COUNT);
    }

    public List<SelectedAssessment> selectWeeklyAssessment(int itemCount) {
        List<AssessmentItem> items = assessmentItemRepository.findAll();
        List<String> weakSkills = statsService.getNeedsPracticeSkills().stream()
                .map(StatsSkillPerformance::skillCategory)
                .toList();
        Map<Long, Integer> attemptCountsByItem = assessmentAttemptRepository.countByItemId();
        return selectWeeklyAssessment(items, weakSkills, attemptCountsByItem, itemCount);
    }

    /**
     * Pure, database-free selection so multi-module coverage and weak-skill prioritization are
     * directly unit testable. Items are first ordered so any item whose skill currently needs
     * practice sorts ahead of the rest (ties broken by module then id for determinism), then drawn
     * round-robin one-per-module so a single heavily-stocked module can never crowd out the others
     * before every module has contributed at least one item.
     */
    static List<SelectedAssessment> selectWeeklyAssessment(List<AssessmentItem> items, List<String> weakSkills,
                                                           Map<Long, Integer> attemptCountsByItem, int itemCount) {
        if (items.isEmpty() || itemCount <= 0) {
            return List.of();
        }

        List<AssessmentItem> prioritized = items.stream()
                .sorted(Comparator
                        .comparing((AssessmentItem item) -> !weakSkills.contains(item.getSkillCategory()))
                        .thenComparing(AssessmentItem::getModuleName)
                        .thenComparingLong(AssessmentItem::getId))
                .toList();

        Map<String, Deque<AssessmentItem>> byModule = new LinkedHashMap<>();
        for (AssessmentItem item : prioritized) {
            byModule.computeIfAbsent(item.getModuleName(), ignored -> new ArrayDeque<>()).add(item);
        }

        List<AssessmentItem> selected = new ArrayList<>();
        boolean addedAny = true;
        while (selected.size() < itemCount && addedAny) {
            addedAny = false;
            for (Deque<AssessmentItem> moduleQueue : byModule.values()) {
                if (selected.size() >= itemCount) {
                    break;
                }
                AssessmentItem next = moduleQueue.poll();
                if (next != null) {
                    selected.add(next);
                    addedAny = true;
                }
            }
        }

        return selected.stream()
                .map(item -> new SelectedAssessment(item,
                        item.variantForAttemptCount(attemptCountsByItem.getOrDefault(item.getId(), 0))))
                .toList();
    }

    public record SelectedAssessment(AssessmentItem item, AssessmentVariant variant) {
    }
}
