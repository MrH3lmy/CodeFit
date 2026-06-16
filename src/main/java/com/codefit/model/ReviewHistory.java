package com.codefit.model;

import java.time.LocalDateTime;

public class ReviewHistory {
    private long id;
    private long flashcardId;
    private ReviewRating rating;
    private int previousIntervalDays;
    private int newIntervalDays;
    private LocalDateTime reviewedAt;
    private boolean submittedInTime;

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt) {
        this(id, flashcardId, rating, previousIntervalDays, newIntervalDays, reviewedAt, true);
    }

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt, boolean submittedInTime) {
        this.id = id;
        this.flashcardId = flashcardId;
        this.rating = rating;
        this.previousIntervalDays = previousIntervalDays;
        this.newIntervalDays = newIntervalDays;
        this.reviewedAt = reviewedAt;
        this.submittedInTime = submittedInTime;
    }

    public ReviewHistory(long flashcardId, ReviewRating rating, int previousIntervalDays, int newIntervalDays) {
        this(0, flashcardId, rating, previousIntervalDays, newIntervalDays, LocalDateTime.now(), true);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getFlashcardId() { return flashcardId; }
    public ReviewRating getRating() { return rating; }
    public int getPreviousIntervalDays() { return previousIntervalDays; }
    public int getNewIntervalDays() { return newIntervalDays; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public boolean isSubmittedInTime() { return submittedInTime; }
}
