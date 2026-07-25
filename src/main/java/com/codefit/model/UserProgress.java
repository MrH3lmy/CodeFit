package com.codefit.model;

import java.time.LocalDate;

public class UserProgress {
    /** Default share (0-100) of leftover session budget reserved for mature-card interleaving from non-focus modules (#110). */
    public static final int DEFAULT_MATURE_INTERLEAVE_PERCENT = 15;

    private long id;
    private int xp;
    private int level;
    private int streakDays;
    private LocalDate lastReviewDate;
    private int totalReviews;
    private int missedDayCount;
    private int streakFreezeCount;
    private boolean recoveryQuestActive;
    private DailyWorkloadMode dailyWorkloadMode;
    private String activeTrainingPath;
    private int focusModuleOrder;
    private int matureInterleavePercent;

    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, 0, 0, false, DailyWorkloadMode.NORMAL,
                null, 0, DEFAULT_MATURE_INTERLEAVE_PERCENT);
    }

    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, missedDayCount, streakFreezeCount,
                recoveryQuestActive, dailyWorkloadMode, null, 0, DEFAULT_MATURE_INTERLEAVE_PERCENT);
    }

    /**
     * @param activeTrainingPath the learner's chosen active training path name, or null if unset
     * @param focusModuleOrder   the chosen focus module's order within that path, or 0 if unset
     * @param matureInterleavePercent share (0-100) of leftover session budget reserved for mature
     *                                cards from modules other than the focus module (#110)
     */
    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode, String activeTrainingPath, int focusModuleOrder,
                        int matureInterleavePercent) {
        this.id = id;
        this.xp = xp;
        this.level = level;
        this.streakDays = streakDays;
        this.lastReviewDate = lastReviewDate;
        this.totalReviews = totalReviews;
        this.missedDayCount = missedDayCount;
        this.streakFreezeCount = streakFreezeCount;
        this.recoveryQuestActive = recoveryQuestActive;
        this.dailyWorkloadMode = dailyWorkloadMode == null ? DailyWorkloadMode.NORMAL : dailyWorkloadMode;
        this.activeTrainingPath = activeTrainingPath;
        this.focusModuleOrder = focusModuleOrder;
        this.matureInterleavePercent = matureInterleavePercent;
    }

    public long getId() { return id; }
    public int getXp() { return xp; }
    public void setXp(int xp) { this.xp = xp; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getLevelRankLabel(String rankTitle) {
        String normalizedRankTitle = rankTitle == null || rankTitle.isBlank() ? "Unranked" : rankTitle.strip();
        return "Level " + level + " · " + normalizedRankTitle;
    }
    public int getStreakDays() { return streakDays; }
    public void setStreakDays(int streakDays) { this.streakDays = streakDays; }
    public LocalDate getLastReviewDate() { return lastReviewDate; }
    public void setLastReviewDate(LocalDate lastReviewDate) { this.lastReviewDate = lastReviewDate; }
    public int getTotalReviews() { return totalReviews; }
    public void setTotalReviews(int totalReviews) { this.totalReviews = totalReviews; }
    public int getMissedDayCount() { return missedDayCount; }
    public void setMissedDayCount(int missedDayCount) { this.missedDayCount = missedDayCount; }
    public int getStreakFreezeCount() { return streakFreezeCount; }
    public void setStreakFreezeCount(int streakFreezeCount) { this.streakFreezeCount = streakFreezeCount; }
    public boolean isRecoveryQuestActive() { return recoveryQuestActive; }
    public void setRecoveryQuestActive(boolean recoveryQuestActive) { this.recoveryQuestActive = recoveryQuestActive; }
    public DailyWorkloadMode getDailyWorkloadMode() { return dailyWorkloadMode; }
    public void setDailyWorkloadMode(DailyWorkloadMode dailyWorkloadMode) {
        this.dailyWorkloadMode = dailyWorkloadMode == null ? DailyWorkloadMode.NORMAL : dailyWorkloadMode;
    }
    public String getActiveTrainingPath() { return activeTrainingPath; }
    public void setActiveTrainingPath(String activeTrainingPath) { this.activeTrainingPath = activeTrainingPath; }
    public int getFocusModuleOrder() { return focusModuleOrder; }
    public void setFocusModuleOrder(int focusModuleOrder) { this.focusModuleOrder = focusModuleOrder; }
    /** True once the learner has explicitly chosen both an active path and a focus module (#110). */
    public boolean hasFocusModule() {
        return activeTrainingPath != null && !activeTrainingPath.isBlank() && focusModuleOrder > 0;
    }
    public int getMatureInterleavePercent() { return matureInterleavePercent; }
    public void setMatureInterleavePercent(int matureInterleavePercent) { this.matureInterleavePercent = matureInterleavePercent; }
}
