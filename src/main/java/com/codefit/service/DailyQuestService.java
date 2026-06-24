package com.codefit.service;

import com.codefit.model.DailyQuest;
import com.codefit.model.DailyQuestObjectiveType;
import com.codefit.model.Flashcard;
import com.codefit.model.UserProgress;
import com.codefit.repository.DailyQuestRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.List;

public class DailyQuestService {
    private static final int DEFAULT_XP_REWARD = 25;
    private static final int REVIEW_QUEST_TARGET = 5;
    private static final int WEAK_SKILL_TARGET = 3;
    private static final int STRETCH_CARD_TARGET = 1;

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
        if (quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL
                && !sameSkill(quest.getSkillCategory(), card.getSkillCategory())) {
            return quest;
        }

        quest.setCurrentCount(Math.min(quest.getTargetCount(), quest.getCurrentCount() + 1));
        completeAndAwardIfReady(quest);
        dailyQuestRepository.update(quest);
        return quest;
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

    private DailyQuest generateQuest(LocalDate questDate) {
        int dueCards = flashcardRepository.countDue();
        if (dueCards > 0) {
            return new DailyQuest(0, questDate, DailyQuestObjectiveType.REVIEW_DUE_CARDS, null,
                    Math.min(REVIEW_QUEST_TARGET, dueCards), 0, false, false, DEFAULT_XP_REWARD);
        }

        List<StatsSkillPerformance> weakSkills = new StatsService().getNeedsPracticeSkills();
        if (!weakSkills.isEmpty()) {
            return new DailyQuest(0, questDate, DailyQuestObjectiveType.PRACTICE_WEAK_SKILL,
                    weakSkills.getFirst().skillCategory(), WEAK_SKILL_TARGET, 0, false, false, DEFAULT_XP_REWARD);
        }

        return new DailyQuest(0, questDate, DailyQuestObjectiveType.ADD_STRETCH_CARDS, null,
                STRETCH_CARD_TARGET, 0, false, false, DEFAULT_XP_REWARD);
    }

    private boolean isReviewObjective(DailyQuest quest) {
        return quest.getObjectiveType() == DailyQuestObjectiveType.REVIEW_DUE_CARDS
                || quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL;
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
        userProgressRepository.save(progress);
        quest.setXpAwarded(true);
    }
}
