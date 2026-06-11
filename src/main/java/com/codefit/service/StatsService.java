package com.codefit.service;

import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;

import java.util.List;

public class StatsService {
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final ProgressService progressService = new ProgressService();

    public UserProgress getProgress() {
        return progressService.getProgress();
    }

    public int getTotalCards() {
        return flashcardRepository.countAll();
    }

    public int getDueCards() {
        return flashcardRepository.countDue();
    }

    public int getReviewedToday() {
        return reviewHistoryRepository.countReviewedToday();
    }

    public List<ReviewHistory> getRecentReviews() {
        return reviewHistoryRepository.findRecent(10);
    }
}
