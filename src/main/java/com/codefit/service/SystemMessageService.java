package com.codefit.service;

import com.codefit.model.DailyQuest;
import com.codefit.model.DailyQuestObjectiveType;
import com.codefit.model.UserProgress;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class SystemMessageService {
    private static final int DEFAULT_DRILL_COUNT = 5;

    public Optional<SystemMessage> highestPriorityDashboardMessage(UserProgress progress,
                                                                    DailyQuest dailyQuest,
                                                                    List<StatsSkillPerformance> weakSkills,
                                                                    boolean weeklyBossAvailable,
                                                                    String rankTitle) {
        return List.of(
                        missedStreakMessage(progress),
                        weakAreaMessage(weakSkills),
                        bossBattleUnlockMessage(weeklyBossAvailable),
                        rankPromotionMessage(progress, rankTitle),
                        dailyQuestMessage(dailyQuest)
                ).stream()
                .flatMap(Optional::stream)
                .filter(SystemMessage::hasText)
                .max(Comparator.comparingInt(SystemMessage::priority));
    }

    public Optional<SystemMessage> dailyQuestMessage(DailyQuest quest) {
        if (quest == null || quest.isCompleted()) {
            return Optional.empty();
        }
        String target = quest.getTargetCount() + " " + pluralize(quest.getTargetCount(), "card");
        if (quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL) {
            return Optional.of(new SystemMessage("Daily quest created: practice " + skillLabel(quest.getSkillCategory())
                    + ". Target: " + target + ".", 20));
        }
        if (quest.getObjectiveType() == DailyQuestObjectiveType.RECOVERY_WEAK_AREAS) {
            return Optional.of(new SystemMessage("Recovery quest active: review " + target + " from "
                    + skillLabel(quest.getSkillCategory()) + ".", 80));
        }
        if (quest.getObjectiveType() == DailyQuestObjectiveType.REVIEW_DUE_CARDS) {
            return Optional.of(new SystemMessage("Daily quest created: review " + target + ".", 20));
        }
        return Optional.of(new SystemMessage("Daily quest created: add " + target + ".", 20));
    }

    public Optional<SystemMessage> weakAreaMessage(List<StatsSkillPerformance> weakSkills) {
        if (weakSkills == null || weakSkills.isEmpty()) {
            return Optional.empty();
        }
        StatsSkillPerformance weakest = weakSkills.getFirst();
        int drillCount = Math.min(DEFAULT_DRILL_COUNT, Math.max(1, weakest.dueCards()));
        return Optional.of(new SystemMessage("Weakness detected: " + weakest.skillCategory()
                + ". Recommended drill: " + drillCount + " " + pluralize(drillCount, "card") + ".", 70));
    }

    public Optional<SystemMessage> missedStreakMessage(UserProgress progress) {
        if (progress == null) {
            return Optional.empty();
        }
        if (progress.isRecoveryQuestActive()) {
            return Optional.of(new SystemMessage("Missed streak detected. Complete the recovery quest to stabilize progress.", 90));
        }
        LocalDate lastReviewDate = progress.getLastReviewDate();
        if (lastReviewDate != null && lastReviewDate.isBefore(LocalDate.now().minusDays(1))) {
            return Optional.of(new SystemMessage("Missed streak detected. Start with a short review session today.", 90));
        }
        return Optional.empty();
    }

    public Optional<SystemMessage> bossBattleUnlockMessage(boolean weeklyBossAvailable) {
        if (!weeklyBossAvailable) {
            return Optional.empty();
        }
        return Optional.of(new SystemMessage("Boss battle unlocked: complete a mixed assessment when ready.", 60));
    }

    public Optional<SystemMessage> rankPromotionMessage(UserProgress progress, String rankTitle) {
        if (progress == null || rankTitle == null || rankTitle.isBlank()) {
            return Optional.empty();
        }
        int currentLevelXp = progress.getXp() % ProgressService.XP_PER_LEVEL;
        if (currentLevelXp > 0 || progress.getTotalReviews() == 0) {
            return Optional.empty();
        }
        return Optional.of(new SystemMessage("Rank promotion: " + rankTitle.strip() + ". Keep the streak active.", 50));
    }

    public String formatSessionCompletionMessage(int reviewedCardCount, int earnedXp, int missedCardCount, boolean weeklyBossMode) {
        if (reviewedCardCount == 0) {
            return "Session note: no cards reviewed.";
        }
        if (missedCardCount > 0) {
            return "Session note: " + missedCardCount + " " + pluralize(missedCardCount, "card")
                    + " flagged for focused follow-up.";
        }
        if (weeklyBossMode) {
            return "Session note: boss battle complete. Review stats for the next focus area.";
        }
        return "Session note: clean session completed. " + earnedXp + " XP added.";
    }

    public String formatBossBattleUnlockMessage() {
        return bossBattleUnlockMessage(true).map(SystemMessage::text).orElse("");
    }

    private String skillLabel(String skillCategory) {
        return skillCategory == null || skillCategory.isBlank() ? "weak areas" : skillCategory.strip();
    }

    private String pluralize(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }
}
