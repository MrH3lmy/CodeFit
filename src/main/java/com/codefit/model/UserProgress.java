package com.codefit.model;

import java.time.LocalDate;

public class UserProgress {
    private long id;
    private int xp;
    private int level;
    private int streakDays;
    private LocalDate lastReviewDate;
    private int totalReviews;

    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews) {
        this.id = id;
        this.xp = xp;
        this.level = level;
        this.streakDays = streakDays;
        this.lastReviewDate = lastReviewDate;
        this.totalReviews = totalReviews;
    }

    public long getId() { return id; }
    public int getXp() { return xp; }
    public void setXp(int xp) { this.xp = xp; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public int getStreakDays() { return streakDays; }
    public void setStreakDays(int streakDays) { this.streakDays = streakDays; }
    public LocalDate getLastReviewDate() { return lastReviewDate; }
    public void setLastReviewDate(LocalDate lastReviewDate) { this.lastReviewDate = lastReviewDate; }
    public int getTotalReviews() { return totalReviews; }
    public void setTotalReviews(int totalReviews) { this.totalReviews = totalReviews; }
}
