package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ReviewHistoryRepository;

import java.util.List;

public class ReviewService {
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();
    private final ReviewHistoryRepository reviewHistoryRepository = new ReviewHistoryRepository();
    private final SpacedRepetitionService spacedRepetitionService = new SpacedRepetitionService();
    private final ProgressService progressService = new ProgressService();

    public List<Flashcard> getDueCards() {
        return flashcardRepository.findDueCards();
    }

    public void review(Flashcard card, ReviewRating rating) {
        int previousInterval = card.getIntervalDays();
        spacedRepetitionService.applyReview(card, rating);
        flashcardRepository.updateSchedule(card);
        reviewHistoryRepository.save(new ReviewHistory(card.getId(), rating, previousInterval, card.getIntervalDays()));
        progressService.recordReview(rating);
    }
}
