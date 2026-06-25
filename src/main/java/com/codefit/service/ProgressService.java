package com.codefit.service;

import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;

public class ProgressService {
    public static final int XP_PER_LEVEL = 100;
    private static final int CONSISTENT_STREAK_DAYS = 7;
    private static final int EXPERIENCED_REVIEW_COUNT = 50;
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();
    private final DailyQuestService dailyQuestService = new DailyQuestService();

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
