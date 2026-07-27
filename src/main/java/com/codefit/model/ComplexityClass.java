package com.codefit.model;

/**
 * A controlled vocabulary for the time/space complexity the learner records as part of post-solve
 * reflection (#146), instead of free text — so trends like "how many O(n^2) solutions this month"
 * are queryable analytics rather than string-parsing.
 */
public enum ComplexityClass {
    O_1,
    O_LOG_N,
    O_N,
    O_N_LOG_N,
    O_N_SQUARED,
    O_N_CUBED,
    O_EXPONENTIAL,
    O_FACTORIAL,
    OTHER
}
