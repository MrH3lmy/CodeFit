package com.codefit.model;

import java.time.LocalDateTime;

/**
 * The single current progress record for a {@link Problem}. Unlike {@link ProblemAttempt} (many
 * rows, one per submission), a problem has exactly one {@code ProblemProgress} row, enforced by a
 * unique constraint on {@code problem_id} in the {@code problem_progress} table; recording a new
 * attempt or updating progress never inserts a second row, only updates this one.
 *
 * <p>{@code perceivedDifficulty} is how hard the learner felt the problem was, independent of
 * {@link RoadmapEntry#getSuggestedLevel()} (how hard the curriculum expects it to be). {@code
 * finalCategory} is a coaching-facing mastery signal, independent of both {@code solvedWith} (how
 * much help was used) and {@code state} (workflow position) — see {@link FinalCategory}.
 */
public class ProblemProgress {
    private long id;
    private long problemId;
    private ProblemState state;
    private DifficultyLevel perceivedDifficulty;
    private SolvedWith solvedWith;
    private FinalCategory finalCategory;
    private String approachNotes;
    private String mistakeNotes;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;

    public ProblemProgress(long id, long problemId, ProblemState state, DifficultyLevel perceivedDifficulty,
                           SolvedWith solvedWith, FinalCategory finalCategory, String approachNotes,
                           String mistakeNotes, LocalDateTime completedAt, LocalDateTime updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.state = state == null ? ProblemState.NOT_STARTED : state;
        this.perceivedDifficulty = perceivedDifficulty;
        this.solvedWith = solvedWith;
        this.finalCategory = finalCategory;
        this.approachNotes = approachNotes;
        this.mistakeNotes = mistakeNotes;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt;
    }

    /** A fresh, not-yet-started progress record for a problem that has just entered the roadmap. */
    public static ProblemProgress notStarted(long problemId) {
        return new ProblemProgress(0, problemId, ProblemState.NOT_STARTED, null, null, null, null, null, null, null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public ProblemState getState() { return state; }
    public void setState(ProblemState state) { this.state = state == null ? ProblemState.NOT_STARTED : state; }
    public DifficultyLevel getPerceivedDifficulty() { return perceivedDifficulty; }
    public void setPerceivedDifficulty(DifficultyLevel perceivedDifficulty) { this.perceivedDifficulty = perceivedDifficulty; }
    public SolvedWith getSolvedWith() { return solvedWith; }
    public void setSolvedWith(SolvedWith solvedWith) { this.solvedWith = solvedWith; }
    public FinalCategory getFinalCategory() { return finalCategory; }
    public void setFinalCategory(FinalCategory finalCategory) { this.finalCategory = finalCategory; }
    public String getApproachNotes() { return approachNotes; }
    public void setApproachNotes(String approachNotes) { this.approachNotes = approachNotes; }
    public String getMistakeNotes() { return mistakeNotes; }
    public void setMistakeNotes(String mistakeNotes) { this.mistakeNotes = mistakeNotes; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
