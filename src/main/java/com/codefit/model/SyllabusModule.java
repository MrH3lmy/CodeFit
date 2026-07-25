package com.codefit.model;

public class SyllabusModule {
    private final String pathName;
    private final int moduleNumber;
    private final String title;
    private final String learningObjective;
    private final long deckId;
    private final String deckName;
    private final int estimatedCardCount;
    private final int seenCardCount;
    private final int learningCardCount;
    private final int masteredCardCount;

    public SyllabusModule(String pathName, int moduleNumber, String title, String learningObjective, long deckId,
                          String deckName, int estimatedCardCount, int seenCardCount, int learningCardCount,
                          int masteredCardCount) {
        this.pathName = pathName;
        this.moduleNumber = moduleNumber;
        this.title = title;
        this.learningObjective = learningObjective;
        this.deckId = deckId;
        this.deckName = deckName;
        this.estimatedCardCount = estimatedCardCount;
        this.seenCardCount = seenCardCount;
        this.learningCardCount = learningCardCount;
        this.masteredCardCount = masteredCardCount;
    }

    public String getPathName() { return pathName; }
    public int getModuleNumber() { return moduleNumber; }
    public String getTitle() { return title; }
    public String getLearningObjective() { return learningObjective; }
    public long getDeckId() { return deckId; }
    public String getDeckName() { return deckName; }
    public int getEstimatedCardCount() { return estimatedCardCount; }
    public int getSeenCardCount() { return seenCardCount; }
    public int getLearningCardCount() { return learningCardCount; }
    public int getMasteredCardCount() { return masteredCardCount; }

    /** Progress reflects durable mastery, not a single attempt. */
    public double getProgress() {
        return estimatedCardCount == 0 ? 0 : masteredCardCount / (double) estimatedCardCount;
    }

    public String getReviewStatus() {
        if (estimatedCardCount == 0) {
            return "No cards available yet";
        }
        if (seenCardCount == 0) {
            return "Not started";
        }
        if (masteredCardCount >= estimatedCardCount) {
            return "Mastered all cards";
        }
        return masteredCardCount + " mastered, " + learningCardCount + " learning, of " + estimatedCardCount + " cards";
    }
}
