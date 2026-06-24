package com.codefit.model;

public class SyllabusModule {
    private final int moduleNumber;
    private final String title;
    private final String learningObjective;
    private final long deckId;
    private final String deckName;
    private final int estimatedCardCount;
    private final int reviewedCardCount;

    public SyllabusModule(int moduleNumber, String title, String learningObjective, long deckId,
                          String deckName, int estimatedCardCount, int reviewedCardCount) {
        this.moduleNumber = moduleNumber;
        this.title = title;
        this.learningObjective = learningObjective;
        this.deckId = deckId;
        this.deckName = deckName;
        this.estimatedCardCount = estimatedCardCount;
        this.reviewedCardCount = reviewedCardCount;
    }

    public int getModuleNumber() { return moduleNumber; }
    public String getTitle() { return title; }
    public String getLearningObjective() { return learningObjective; }
    public long getDeckId() { return deckId; }
    public String getDeckName() { return deckName; }
    public int getEstimatedCardCount() { return estimatedCardCount; }
    public int getReviewedCardCount() { return reviewedCardCount; }

    public double getProgress() {
        return estimatedCardCount == 0 ? 0 : reviewedCardCount / (double) estimatedCardCount;
    }

    public String getReviewStatus() {
        if (estimatedCardCount == 0) {
            return "No cards available yet";
        }
        if (reviewedCardCount == 0) {
            return "Not started";
        }
        if (reviewedCardCount >= estimatedCardCount) {
            return "Reviewed all cards";
        }
        return reviewedCardCount + " of " + estimatedCardCount + " reviewed";
    }
}
