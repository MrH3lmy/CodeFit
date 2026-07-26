package com.codefit.model;

/**
 * A simple three-point difficulty scale shared by two distinct concepts: the workbook's own
 * suggested level for a {@link RoadmapEntry} position, and the learner's self-reported
 * {@code perceivedDifficulty} on {@link ProblemProgress} after attempting it. Sharing one enum keeps
 * "how hard is this expected to be" and "how hard did it feel" directly comparable.
 */
public enum DifficultyLevel {
    EASY,
    MEDIUM,
    HARD
}
