package com.codefit.model;

import java.time.LocalDate;

public class DailyQuest {
    private long id;
    private LocalDate questDate;
    private DailyQuestObjectiveType objectiveType;
    private String skillCategory;
    private int targetCount;
    private int currentCount;
    private boolean completed;
    private boolean xpAwarded;
    private int xpReward;

    public DailyQuest(long id, LocalDate questDate, DailyQuestObjectiveType objectiveType, String skillCategory,
                      int targetCount, int currentCount, boolean completed, boolean xpAwarded, int xpReward) {
        this.id = id;
        this.questDate = questDate;
        this.objectiveType = objectiveType;
        this.skillCategory = skillCategory;
        this.targetCount = targetCount;
        this.currentCount = currentCount;
        this.completed = completed;
        this.xpAwarded = xpAwarded;
        this.xpReward = xpReward;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public LocalDate getQuestDate() { return questDate; }
    public DailyQuestObjectiveType getObjectiveType() { return objectiveType; }
    public void setObjectiveType(DailyQuestObjectiveType objectiveType) { this.objectiveType = objectiveType; }
    public String getSkillCategory() { return skillCategory; }
    public void setSkillCategory(String skillCategory) { this.skillCategory = skillCategory; }
    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }
    public int getCurrentCount() { return currentCount; }
    public void setCurrentCount(int currentCount) { this.currentCount = currentCount; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public boolean isXpAwarded() { return xpAwarded; }
    public void setXpAwarded(boolean xpAwarded) { this.xpAwarded = xpAwarded; }
    public int getXpReward() { return xpReward; }
    public void setXpReward(int xpReward) { this.xpReward = xpReward; }
}
