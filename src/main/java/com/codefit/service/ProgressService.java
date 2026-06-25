package com.codefit.service;

import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;
import java.util.prefs.Preferences;

public class ProgressService {
    public static final int XP_PER_LEVEL = 100;
    private static final int CONSISTENT_STREAK_DAYS = 7;
    private static final int EXPERIENCED_REVIEW_COUNT = 50;
    public static final int REFLECTION_CARD_XP = 5;
    public static final int REFLECTION_CARD_DAILY_XP_CAP = 15;
    private static final String REFLECTION_XP_DATE_KEY = "reflectionXpDate";
    private static final String REFLECTION_XP_TOTAL_KEY = "reflectionXpTotal";
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();
    private final DailyQuestService dailyQuestService = new DailyQuestService();
    private final Preferences preferences = Preferences.userNodeForPackage(ProgressService.class);

    public UserProgress getProgress() {
        return userProgressRepository.getProgress();
    }

    public String getRankTitle(UserProgress progress) {
        if (progress == null) {
            return "E-Rank Backend Starter";
        }

        int level = progress.getLevel();
        boolean consistent = progress.getStreakDays() >= CONSISTENT_STREAK_DAYS
                || progress.getTotalReviews() >= EXPERIENCED_REVIEW_COUNT;

        if (level >= 30 && consistent) {
            return "S-Rank Backend Architect";
        }
        if (level >= 24 && consistent) {
            return "A-Rank Backend Lead";
        }
        if (level >= 18 && consistent) {
            return "B-Rank Backend Specialist";
        }
        if (level >= 10) {
            return "C-Rank Backend Builder";
        }
        if (level >= 5) {
            return "D-Rank Backend Apprentice";
        }
        return "E-Rank Backend Starter";
    }

    public int recordReflectionCardCreated() {
        LocalDate today = LocalDate.now();
        int awardedToday = getReflectionXpAwardedToday(today);
        int award = Math.min(REFLECTION_CARD_XP, REFLECTION_CARD_DAILY_XP_CAP - awardedToday);
        if (award <= 0) {
            return 0;
        }

        UserProgress progress = userProgressRepository.getProgress();
        progress.setXp(progress.getXp() + award);
        progress.setLevel((progress.getXp() / XP_PER_LEVEL) + 1);
        userProgressRepository.save(progress);
        preferences.put(REFLECTION_XP_DATE_KEY, today.toString());
        preferences.putInt(REFLECTION_XP_TOTAL_KEY, awardedToday + award);
        return award;
    }

    private int getReflectionXpAwardedToday(LocalDate today) {
        String awardDate = preferences.get(REFLECTION_XP_DATE_KEY, "");
        if (!today.toString().equals(awardDate)) {
            return 0;
        }
        return preferences.getInt(REFLECTION_XP_TOTAL_KEY, 0);
    }

    public UserProgress recordReview(ReviewRating rating) {
        UserProgress progress = userProgressRepository.getProgress();
        LocalDate today = LocalDate.now();
        LocalDate lastReviewDate = progress.getLastReviewDate();

        progress.setXp(progress.getXp() + rating.getXp());
        progress.setLevel((progress.getXp() / XP_PER_LEVEL) + 1);
        progress.setTotalReviews(progress.getTotalReviews() + 1);

        if (lastReviewDate == null) {
            progress.setStreakDays(1);
            progress.setMissedDayCount(0);
            progress.setRecoveryQuestActive(false);
        } else if (lastReviewDate.equals(today)) {
            progress.setStreakDays(Math.max(1, progress.getStreakDays()));
        } else if (lastReviewDate.plusDays(1).equals(today)) {
            progress.setStreakDays(progress.getStreakDays() + 1);
            progress.setMissedDayCount(0);
            progress.setRecoveryQuestActive(false);
        } else if (lastReviewDate.isBefore(today.minusDays(1))) {
            progress.setMissedDayCount((int) java.time.temporal.ChronoUnit.DAYS.between(lastReviewDate, today) - 1);
            progress.setStreakFreezeCount(progress.getStreakFreezeCount() + 1);
            progress.setRecoveryQuestActive(true);
            progress.setStreakDays(Math.max(1, progress.getStreakDays()));
            dailyQuestService.activateRecoveryQuest();
        }

        progress.setLastReviewDate(today);
        userProgressRepository.save(progress);
        return progress;
    }
}
