package com.codefit.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class Flashcard {
    private long id;
    private long deckId;
    private String front;
    private String back;
    private LocalDate dueDate;
    private int intervalDays;
    private double easeFactor;
    private int reviewCount;
    private LocalDateTime createdAt;

    public Flashcard(long id, long deckId, String front, String back, LocalDate dueDate,
                     int intervalDays, double easeFactor, int reviewCount, LocalDateTime createdAt) {
        this.id = id;
        this.deckId = deckId;
        this.front = front;
        this.back = back;
        this.dueDate = dueDate;
        this.intervalDays = intervalDays;
        this.easeFactor = easeFactor;
        this.reviewCount = reviewCount;
        this.createdAt = createdAt;
    }

    public Flashcard(long deckId, String front, String back) {
        this(0, deckId, front, back, LocalDate.now(), 0, 2.5, 0, LocalDateTime.now());
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getDeckId() { return deckId; }
    public void setDeckId(long deckId) { this.deckId = deckId; }
    public String getFront() { return front; }
    public void setFront(String front) { this.front = front; }
    public String getBack() { return back; }
    public void setBack(String back) { this.back = back; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }
    public double getEaseFactor() { return easeFactor; }
    public void setEaseFactor(double easeFactor) { this.easeFactor = easeFactor; }
    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
