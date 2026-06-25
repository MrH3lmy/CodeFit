package com.codefit.service;

import com.codefit.model.DailyQuest;
import com.codefit.model.DailyQuestObjectiveType;
import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.Flashcard;
import com.codefit.model.UserProgress;
import com.codefit.repository.DailyQuestRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.List;

public class DailyQuestService {
    private static final int DEFAULT_XP_REWARD = 25;
    private static final int RECOVERY_WEAK_AREA_TARGET = 10;
    private static final int RECOVERY_XP_REWARD = 50;

    private final DailyQuestRepository dailyQuestRepository = new DailyQuestRepository();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();

    public DailyQuest getActiveQuest() {
        LocalDate today = LocalDate.now();
        return dailyQuestRepository.findByDate(today).orElseGet(() -> dailyQuestRepository.save(generateQuest(today)));
    }

    public DailyQuest recordReview(Flashcard card) {
        DailyQuest quest = getActiveQuest();
        if (quest.isCompleted() || !isReviewObjective(quest)) {
            return quest;
        }
        if ((quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL
                || quest.getObjectiveType() == DailyQuestObjectiveType.RECOVERY_WEAK_AREAS)
                && quest.getSkillCategory() != null
                && !sameSkill(quest.getSkillCategory(), card.getSkillCategory())) {
            return quest;
        }

        quest.setCurrentCount(Math.min(quest.getTargetCount(), quest.getCurrentCount() + 1));
        completeAndAwardIfReady(quest);
        dailyQuestRepository.update(quest);
        return quest;
    }

    public DailyQuest activateRecoveryQuest() {
        LocalDate today = LocalDate.now();
        DailyQuest recoveryQuest = buildRecoveryQuest(today);
        return dailyQuestRepository.findByDate(today)
                .map(existingQuest -> {
                    existingQuest.setObjectiveType(recoveryQuest.getObjectiveType());
                    existingQuest.setSkillCategory(recoveryQuest.getSkillCategory());
                    existingQuest.setTargetCount(recoveryQuest.getTargetCount());
                    existingQuest.setCurrentCount(0);
                    existingQuest.setCompleted(false);
                    existingQuest.setXpAwarded(false);
                    existingQuest.setXpReward(recoveryQuest.getXpReward());
                    dailyQuestRepository.update(existingQuest);
                    return existingQuest;
                })
                .orElseGet(() -> dailyQuestRepository.save(recoveryQuest));
    }

    public DailyQuest recordCardAdded() {
        DailyQuest quest = getActiveQuest();
        if (quest.isCompleted() || quest.getObjectiveType() != DailyQuestObjectiveType.ADD_STRETCH_CARDS) {
            return quest;
        }
        quest.setCurrentCount(Math.min(quest.getTargetCount(), quest.getCurrentCount() + 1));
        completeAndAwardIfReady(quest);
        dailyQuestRepository.update(quest);
        return quest;
    }

    private DailyQuest buildRecoveryQuest(LocalDate questDate) {
        List<StatsSkillPerformance> weakSkills = new StatsService().getNeedsPracticeSkills();
        String recoverySkill = weakSkills.isEmpty() ? null : weakSkills.getFirst().skillCategory();
        DailyWorkloadMode mode = userProgressRepository.getProgress().getDailyWorkloadMode();
        int targetCount = Math.min(RECOVERY_WEAK_AREA_TARGET, Math.max(mode.getWeakSkillQuestTarget(), mode.getReviewSessionLimit()));
        return new DailyQuest(0, questDate, DailyQuestObjectiveType.RECOVERY_WEAK_AREAS, recoverySkill,
                targetCount, 0, false, false, RECOVERY_XP_REWARD);
    }

    private DailyQuest generateQuest(LocalDate questDate) {
        DailyWorkloadMode mode = userProgressRepository.getProgress().getDailyWorkloadMode();
        int dueCards = flashcardRepository.countDue();
        if (dueCards > 0) {
            return new DailyQuest(0, questDate, DailyQuestObjectiveType.REVIEW_DUE_CARDS, null,
                    Math.min(mode.getDueReviewQuestTarget(), dueCards), 0, false, false, DEFAULT_XP_REWARD);
        }

        List<StatsSkillPerformance> weakSkills = new StatsService().getNeedsPracticeSkills();
        if (!weakSkills.isEmpty()) {
            return new DailyQuest(0, questDate, DailyQuestObjectiveType.PRACTICE_WEAK_SKILL,
                    weakSkills.getFirst().skillCategory(), mode.getWeakSkillQuestTarget(), 0, false, false, DEFAULT_XP_REWARD);
        }

        return new DailyQuest(0, questDate, DailyQuestObjectiveType.ADD_STRETCH_CARDS, null,
                mode.getStretchCardQuestTarget(), 0, false, false, DEFAULT_XP_REWARD);
    }

    private boolean isReviewObjective(DailyQuest quest) {
        return quest.getObjectiveType() == DailyQuestObjectiveType.REVIEW_DUE_CARDS
                || quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL
                || quest.getObjectiveType() == DailyQuestObjectiveType.RECOVERY_WEAK_AREAS;
    }

    private boolean sameSkill(String questSkill, String cardSkill) {
        String normalizedQuestSkill = normalizeSkill(questSkill);
        String normalizedCardSkill = normalizeSkill(cardSkill);
        return normalizedQuestSkill.equalsIgnoreCase(normalizedCardSkill);
    }

    private String normalizeSkill(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "General" : skillCategory.strip();
    }

    private void completeAndAwardIfReady(DailyQuest quest) {
        if (quest.getCurrentCount() < quest.getTargetCount()) {
            return;
        }
        quest.setCompleted(true);
        if (quest.isXpAwarded()) {
            return;
        }
        UserProgress progress = userProgressRepository.getProgress();
        progress.setXp(progress.getXp() + quest.getXpReward());
        progress.setLevel((progress.getXp() / ProgressService.XP_PER_LEVEL) + 1);
        if (quest.getObjectiveType() == DailyQuestObjectiveType.RECOVERY_WEAK_AREAS) {
            progress.setRecoveryQuestActive(false);
            progress.setMissedDayCount(0);
        }
        userProgressRepository.save(progress);
        quest.setXpAwarded(true);
    }
}
