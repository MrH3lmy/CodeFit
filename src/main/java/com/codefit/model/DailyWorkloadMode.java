package com.codefit.model;

public enum DailyWorkloadMode {
    MINIMUM("Minimum", 2, 1, 1, 5),
    NORMAL("Normal", 5, 3, 1, 10),
    DEEP("Deep", 10, 5, 2, 20),
    RECOVERY("Recovery", 3, 2, 1, 3);

    private final String displayName;
    private final int dueReviewQuestTarget;
    private final int weakSkillQuestTarget;
    private final int stretchCardQuestTarget;
    private final int reviewSessionLimit;

    DailyWorkloadMode(String displayName, int dueReviewQuestTarget, int weakSkillQuestTarget,
                      int stretchCardQuestTarget, int reviewSessionLimit) {
        this.displayName = displayName;
        this.dueReviewQuestTarget = dueReviewQuestTarget;
        this.weakSkillQuestTarget = weakSkillQuestTarget;
        this.stretchCardQuestTarget = stretchCardQuestTarget;
        this.reviewSessionLimit = reviewSessionLimit;
    }

    public String getDisplayName() { return displayName; }
    public int getDueReviewQuestTarget() { return dueReviewQuestTarget; }
    public int getWeakSkillQuestTarget() { return weakSkillQuestTarget; }
    public int getStretchCardQuestTarget() { return stretchCardQuestTarget; }
    public int getReviewSessionLimit() { return reviewSessionLimit; }

    public String getSummary() {
        return displayName + " · up to " + reviewSessionLimit + " review cards";
    }

    public static DailyWorkloadMode fromDatabaseValue(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return DailyWorkloadMode.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return NORMAL;
        }
    }
}
