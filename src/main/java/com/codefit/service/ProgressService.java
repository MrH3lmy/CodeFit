package com.codefit.service;

import com.codefit.model.ReviewRating;
import com.codefit.model.UserProgress;
import com.codefit.repository.UserProgressRepository;

import java.time.LocalDate;

public class ProgressService {
    public static final int XP_PER_LEVEL = 100;
    private final UserProgressRepository userProgressRepository = new UserProgressRepository();

    public UserProgress getProgress() {
        return userProgressRepository.getProgress();
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
        } else if (lastReviewDate.equals(today)) {
            progress.setStreakDays(Math.max(1, progress.getStreakDays()));
        } else if (lastReviewDate.plusDays(1).equals(today)) {
            progress.setStreakDays(progress.getStreakDays() + 1);
        } else {
            progress.setStreakDays(1);
        }

        progress.setLastReviewDate(today);
        userProgressRepository.save(progress);
        return progress;
    }
}
