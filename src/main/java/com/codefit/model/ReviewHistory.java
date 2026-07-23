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
    private boolean bossBattle;
    private String validationResult;
    private String submittedAnswer;
    private Integer responseTimeMs;
    private boolean hintUsed;
    private String sessionId;

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt) {
        this(id, flashcardId, rating, previousIntervalDays, newIntervalDays, reviewedAt, true, false);
    }

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt, boolean submittedInTime) {
        this(id, flashcardId, rating, previousIntervalDays, newIntervalDays, reviewedAt, submittedInTime, false);
    }

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt, boolean submittedInTime, boolean bossBattle) {
        this(id, flashcardId, rating, previousIntervalDays, newIntervalDays, reviewedAt, submittedInTime, bossBattle,
                null, null, null, false, null);
    }

    public ReviewHistory(long id, long flashcardId, ReviewRating rating, int previousIntervalDays,
                         int newIntervalDays, LocalDateTime reviewedAt, boolean submittedInTime, boolean bossBattle,
                         String validationResult, String submittedAnswer, Integer responseTimeMs,
                         boolean hintUsed, String sessionId) {
        this.id = id;
        this.flashcardId = flashcardId;
        this.rating = rating;
        this.previousIntervalDays = previousIntervalDays;
        this.newIntervalDays = newIntervalDays;
        this.reviewedAt = reviewedAt;
        this.submittedInTime = submittedInTime;
        this.bossBattle = bossBattle;
        this.validationResult = validationResult;
        this.submittedAnswer = submittedAnswer;
        this.responseTimeMs = responseTimeMs;
        this.hintUsed = hintUsed;
        this.sessionId = sessionId;
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
    public boolean isBossBattle() { return bossBattle; }
    public String getValidationResult() { return validationResult; }
    public String getSubmittedAnswer() { return submittedAnswer; }
    public Integer getResponseTimeMs() { return responseTimeMs; }
    public boolean isHintUsed() { return hintUsed; }
    public String getSessionId() { return sessionId; }

    /**
     * Whether the attempt was objectively validated as correct. Legacy rows saved before
     * validation_result existed fall back to the self-selected rating so old data stays readable.
     */
    public boolean isObjectivelyCorrect() {
        if (validationResult == null || validationResult.isBlank()) {
            return rating == ReviewRating.GOOD || rating == ReviewRating.EASY;
        }
        return switch (validationResult) {
            case "EXACT", "CLOSE_SPACING", "TIMED_OUT_WITH_ATTEMPT" -> true;
            default -> false;
        };
    }

    /** A timed success requires both an in-time submission and an objectively correct answer. */
    public boolean isTimedSuccess() {
        return submittedInTime && isObjectivelyCorrect();
    }
}
