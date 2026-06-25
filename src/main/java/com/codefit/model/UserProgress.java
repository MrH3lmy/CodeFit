package com.codefit.model;

import java.time.LocalDate;

public class UserProgress {
    private long id;
    private int xp;
    private int level;
    private int streakDays;
    private LocalDate lastReviewDate;
    private int totalReviews;
    private int missedDayCount;
    private int streakFreezeCount;
    private boolean recoveryQuestActive;

    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, 0, 0, false);
    }

    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive) {
        this.id = id;
        this.xp = xp;
        this.level = level;
        this.streakDays = streakDays;
        this.lastReviewDate = lastReviewDate;
        this.totalReviews = totalReviews;
        this.missedDayCount = missedDayCount;
        this.streakFreezeCount = streakFreezeCount;
        this.recoveryQuestActive = recoveryQuestActive;
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
}
