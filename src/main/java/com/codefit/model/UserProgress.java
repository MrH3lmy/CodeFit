package com.codefit.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class UserProgress {
    /** Default share (0-100) of leftover session budget reserved for mature-card interleaving from non-focus modules (#110). */
    public static final int DEFAULT_MATURE_INTERLEAVE_PERCENT = 15;
    /** Default daily new-card cap, matching {@code ReviewService.DEFAULT_DAILY_NEW_CARD_LIMIT}; duplicated here rather
     *  than referenced across the model/service boundary, the same way DEFAULT_MATURE_INTERLEAVE_PERCENT already is. */
    public static final int DEFAULT_DAILY_NEW_CARD_LIMIT = 2;
    /** Default guided-routine session length in minutes, matching {@code SessionBudgetService.STANDARD_MINUTES} (#111). */
    public static final int DEFAULT_GUIDED_SESSION_MINUTES = 15;
    /** Default solving-workspace coaching checkpoints, in minutes of total elapsed session time (#145). */
    public static final String DEFAULT_SOLVING_CHECKPOINT_MINUTES = "20,60,120";
    /** Default daily target for the guided curriculum practice loop, in problems (#161). */
    public static final int DEFAULT_DAILY_TARGET_PROBLEMS = 3;
    /** Default Java runner wall-clock timeout in seconds, matching {@code RunLimits.defaults()} (#163). */
    public static final int DEFAULT_JAVA_RUN_TIMEOUT_SECONDS = 5;

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
    private int dailyNewCardLimit;
    private int guidedSessionMinutes;
    private boolean solvingCheckpointsEnabled;
    private String solvingCheckpointMinutesCsv;
    private int dailyTargetProblems;
    private int javaRunTimeoutSeconds;

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
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, missedDayCount, streakFreezeCount,
                recoveryQuestActive, dailyWorkloadMode, activeTrainingPath, focusModuleOrder, matureInterleavePercent,
                DEFAULT_DAILY_NEW_CARD_LIMIT, DEFAULT_GUIDED_SESSION_MINUTES);
    }

    /**
     * @param dailyNewCardLimit     max new cards introduced per day, so the guided routine (#111) and
     *                              every other review path respect a learner-chosen cap rather than a
     *                              hardcoded one
     * @param guidedSessionMinutes  the learner's preferred guided-routine session length in minutes (#111)
     */
    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode, String activeTrainingPath, int focusModuleOrder,
                        int matureInterleavePercent, int dailyNewCardLimit, int guidedSessionMinutes) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, missedDayCount, streakFreezeCount,
                recoveryQuestActive, dailyWorkloadMode, activeTrainingPath, focusModuleOrder, matureInterleavePercent,
                dailyNewCardLimit, guidedSessionMinutes, true, DEFAULT_SOLVING_CHECKPOINT_MINUTES);
    }

    /**
     * @param solvingCheckpointsEnabled  whether the solving workspace (#145) shows coaching
     *                                   checkpoint reminders at all
     * @param solvingCheckpointMinutesCsv ascending comma-separated total-elapsed-minute thresholds
     *                                    (e.g. {@code "20,60,120"}) at which a reminder is shown
     */
    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode, String activeTrainingPath, int focusModuleOrder,
                        int matureInterleavePercent, int dailyNewCardLimit, int guidedSessionMinutes,
                        boolean solvingCheckpointsEnabled, String solvingCheckpointMinutesCsv) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, missedDayCount, streakFreezeCount,
                recoveryQuestActive, dailyWorkloadMode, activeTrainingPath, focusModuleOrder, matureInterleavePercent,
                dailyNewCardLimit, guidedSessionMinutes, solvingCheckpointsEnabled, solvingCheckpointMinutesCsv,
                DEFAULT_DAILY_TARGET_PROBLEMS);
    }

    /** @param dailyTargetProblems the learner's preferred number of problems per day for the guided
     *                             curriculum practice loop's daily target (#161) */
    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode, String activeTrainingPath, int focusModuleOrder,
                        int matureInterleavePercent, int dailyNewCardLimit, int guidedSessionMinutes,
                        boolean solvingCheckpointsEnabled, String solvingCheckpointMinutesCsv, int dailyTargetProblems) {
        this(id, xp, level, streakDays, lastReviewDate, totalReviews, missedDayCount, streakFreezeCount,
                recoveryQuestActive, dailyWorkloadMode, activeTrainingPath, focusModuleOrder, matureInterleavePercent,
                dailyNewCardLimit, guidedSessionMinutes, solvingCheckpointsEnabled, solvingCheckpointMinutesCsv,
                dailyTargetProblems, DEFAULT_JAVA_RUN_TIMEOUT_SECONDS);
    }

    /** @param javaRunTimeoutSeconds the learner's preferred Java runner wall-clock timeout, in
     *                               seconds — "Infinite loops are terminated by a configurable
     *                               timeout" (#163); passed as {@link com.codefit.service.RunLimits}'s
     *                               {@code timeoutSeconds} instead of the fixed default. */
    public UserProgress(long id, int xp, int level, int streakDays, LocalDate lastReviewDate, int totalReviews,
                        int missedDayCount, int streakFreezeCount, boolean recoveryQuestActive,
                        DailyWorkloadMode dailyWorkloadMode, String activeTrainingPath, int focusModuleOrder,
                        int matureInterleavePercent, int dailyNewCardLimit, int guidedSessionMinutes,
                        boolean solvingCheckpointsEnabled, String solvingCheckpointMinutesCsv, int dailyTargetProblems,
                        int javaRunTimeoutSeconds) {
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
        this.dailyNewCardLimit = dailyNewCardLimit;
        this.guidedSessionMinutes = guidedSessionMinutes;
        this.solvingCheckpointsEnabled = solvingCheckpointsEnabled;
        this.solvingCheckpointMinutesCsv = solvingCheckpointMinutesCsv == null || solvingCheckpointMinutesCsv.isBlank()
                ? DEFAULT_SOLVING_CHECKPOINT_MINUTES : solvingCheckpointMinutesCsv;
        this.dailyTargetProblems = dailyTargetProblems > 0 ? dailyTargetProblems : DEFAULT_DAILY_TARGET_PROBLEMS;
        this.javaRunTimeoutSeconds = javaRunTimeoutSeconds > 0 ? javaRunTimeoutSeconds : DEFAULT_JAVA_RUN_TIMEOUT_SECONDS;
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
    public int getDailyNewCardLimit() { return dailyNewCardLimit; }
    public void setDailyNewCardLimit(int dailyNewCardLimit) { this.dailyNewCardLimit = dailyNewCardLimit; }
    public int getGuidedSessionMinutes() { return guidedSessionMinutes; }
    public void setGuidedSessionMinutes(int guidedSessionMinutes) { this.guidedSessionMinutes = guidedSessionMinutes; }
    public boolean isSolvingCheckpointsEnabled() { return solvingCheckpointsEnabled; }
    public void setSolvingCheckpointsEnabled(boolean solvingCheckpointsEnabled) { this.solvingCheckpointsEnabled = solvingCheckpointsEnabled; }
    public String getSolvingCheckpointMinutesCsv() { return solvingCheckpointMinutesCsv; }
    public void setSolvingCheckpointMinutesCsv(String solvingCheckpointMinutesCsv) {
        this.solvingCheckpointMinutesCsv = solvingCheckpointMinutesCsv == null || solvingCheckpointMinutesCsv.isBlank()
                ? DEFAULT_SOLVING_CHECKPOINT_MINUTES : solvingCheckpointMinutesCsv;
    }

    public int getDailyTargetProblems() { return dailyTargetProblems; }
    public void setDailyTargetProblems(int dailyTargetProblems) {
        this.dailyTargetProblems = dailyTargetProblems > 0 ? dailyTargetProblems : DEFAULT_DAILY_TARGET_PROBLEMS;
    }

    public int getJavaRunTimeoutSeconds() { return javaRunTimeoutSeconds; }
    public void setJavaRunTimeoutSeconds(int javaRunTimeoutSeconds) {
        this.javaRunTimeoutSeconds = javaRunTimeoutSeconds > 0 ? javaRunTimeoutSeconds : DEFAULT_JAVA_RUN_TIMEOUT_SECONDS;
    }

    /** Parses {@link #getSolvingCheckpointMinutesCsv()} into ascending minute thresholds, ignoring any malformed entries. */
    public List<Integer> getSolvingCheckpointMinutes() {
        List<Integer> minutes = new ArrayList<>();
        for (String token : solvingCheckpointMinutesCsv.split(",")) {
            try {
                int value = Integer.parseInt(token.strip());
                if (value > 0) {
                    minutes.add(value);
                }
            } catch (NumberFormatException ignored) {
                // malformed token from a hand-edited preference value; skip rather than fail the whole list
            }
        }
        minutes.sort(Integer::compareTo);
        return minutes;
    }
}
