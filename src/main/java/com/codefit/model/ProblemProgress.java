package com.codefit.model;

import java.time.LocalDateTime;

/**
 * The single current progress record for a {@link Problem}. Unlike {@link ProblemAttempt} (many
 * rows, one per submission), a problem has exactly one {@code ProblemProgress} row, enforced by a
 * unique constraint on {@code problem_id} in the {@code problem_progress} table; recording a new
 * attempt or updating progress never inserts a second row, only updates this one.
 *
 * <p>Fields split into two groups, updated through two different service methods
 * ({@code ProblemProgressService#updateProgress} and {@code #updateReflection}) so "what workflow
 * state is this problem in" stays clearly distinct from "what did the learner think/learn" (#146):
 *
 * <ul>
 *   <li><b>Workflow state</b>: {@code state}, {@code completedAt} — set by the solving workspace's
 *       finish action ({@code ProblemSolvingWorkspaceService#finish}), never by the reflection form.</li>
 *   <li><b>Post-solve reflection</b>: everything else. Entirely optional, and editable at any time
 *       after the fact, independent of workflow state — recording a reflection never changes
 *       {@code state} or {@code completedAt}, and finishing a session never blanks out an
 *       already-recorded reflection.</li>
 * </ul>
 *
 * <p>{@code perceivedDifficultyRating} (1-10) is the learner's own self-rated difficulty, a
 * different, more granular axis than {@link RoadmapEntry#getSuggestedLevel()} (the curriculum's
 * suggested {@link DifficultyLevel} for the roadmap position). {@code actualTopic} is likewise
 * distinct from {@link Problem#getTopic()}: it's what technique the learner felt they actually
 * ended up using, which need not match the problem's catalog topic.
 */
public class ProblemProgress {
    private long id;
    private long problemId;
    private ProblemState state;
    private Integer perceivedDifficultyRating;
    private SolvedWith solvedWith;
    private FinalCategory finalCategory;
    private String approachNotes;
    private String mistakeNotes;
    private String importantObservation;
    private ComplexityClass timeComplexity;
    private ComplexityClass spaceComplexity;
    private String lessonLearned;
    private String actualTopic;
    private boolean editorialUnderstood;
    private boolean otherSolutionsReviewed;
    private boolean simplerImplementationConsidered;
    private boolean betterComplexityConsidered;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;

    public ProblemProgress(long id, long problemId, ProblemState state, Integer perceivedDifficultyRating,
                           SolvedWith solvedWith, FinalCategory finalCategory, String approachNotes,
                           String mistakeNotes, String importantObservation, ComplexityClass timeComplexity,
                           ComplexityClass spaceComplexity, String lessonLearned, String actualTopic,
                           boolean editorialUnderstood, boolean otherSolutionsReviewed,
                           boolean simplerImplementationConsidered, boolean betterComplexityConsidered,
                           LocalDateTime completedAt, LocalDateTime updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.state = state == null ? ProblemState.NOT_STARTED : state;
        this.perceivedDifficultyRating = perceivedDifficultyRating;
        this.solvedWith = solvedWith;
        this.finalCategory = finalCategory;
        this.approachNotes = approachNotes;
        this.mistakeNotes = mistakeNotes;
        this.importantObservation = importantObservation;
        this.timeComplexity = timeComplexity;
        this.spaceComplexity = spaceComplexity;
        this.lessonLearned = lessonLearned;
        this.actualTopic = actualTopic;
        this.editorialUnderstood = editorialUnderstood;
        this.otherSolutionsReviewed = otherSolutionsReviewed;
        this.simplerImplementationConsidered = simplerImplementationConsidered;
        this.betterComplexityConsidered = betterComplexityConsidered;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt;
    }

    /** A fresh, not-yet-started progress record for a problem that has just entered the roadmap. */
    public static ProblemProgress notStarted(long problemId) {
        return new ProblemProgress(0, problemId, ProblemState.NOT_STARTED, null, null, null, null, null,
                null, null, null, null, null, false, false, false, false, null, null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public ProblemState getState() { return state; }
    public void setState(ProblemState state) { this.state = state == null ? ProblemState.NOT_STARTED : state; }
    public Integer getPerceivedDifficultyRating() { return perceivedDifficultyRating; }
    public void setPerceivedDifficultyRating(Integer perceivedDifficultyRating) { this.perceivedDifficultyRating = perceivedDifficultyRating; }
    public SolvedWith getSolvedWith() { return solvedWith; }
    public void setSolvedWith(SolvedWith solvedWith) { this.solvedWith = solvedWith; }
    public FinalCategory getFinalCategory() { return finalCategory; }
    public void setFinalCategory(FinalCategory finalCategory) { this.finalCategory = finalCategory; }
    public String getApproachNotes() { return approachNotes; }
    public void setApproachNotes(String approachNotes) { this.approachNotes = approachNotes; }
    public String getMistakeNotes() { return mistakeNotes; }
    public void setMistakeNotes(String mistakeNotes) { this.mistakeNotes = mistakeNotes; }
    public String getImportantObservation() { return importantObservation; }
    public void setImportantObservation(String importantObservation) { this.importantObservation = importantObservation; }
    public ComplexityClass getTimeComplexity() { return timeComplexity; }
    public void setTimeComplexity(ComplexityClass timeComplexity) { this.timeComplexity = timeComplexity; }
    public ComplexityClass getSpaceComplexity() { return spaceComplexity; }
    public void setSpaceComplexity(ComplexityClass spaceComplexity) { this.spaceComplexity = spaceComplexity; }
    public String getLessonLearned() { return lessonLearned; }
    public void setLessonLearned(String lessonLearned) { this.lessonLearned = lessonLearned; }
    public String getActualTopic() { return actualTopic; }
    public void setActualTopic(String actualTopic) { this.actualTopic = actualTopic; }
    public boolean isEditorialUnderstood() { return editorialUnderstood; }
    public void setEditorialUnderstood(boolean editorialUnderstood) { this.editorialUnderstood = editorialUnderstood; }
    public boolean isOtherSolutionsReviewed() { return otherSolutionsReviewed; }
    public void setOtherSolutionsReviewed(boolean otherSolutionsReviewed) { this.otherSolutionsReviewed = otherSolutionsReviewed; }
    public boolean isSimplerImplementationConsidered() { return simplerImplementationConsidered; }
    public void setSimplerImplementationConsidered(boolean simplerImplementationConsidered) { this.simplerImplementationConsidered = simplerImplementationConsidered; }
    public boolean isBetterComplexityConsidered() { return betterComplexityConsidered; }
    public void setBetterComplexityConsidered(boolean betterComplexityConsidered) { this.betterComplexityConsidered = betterComplexityConsidered; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
