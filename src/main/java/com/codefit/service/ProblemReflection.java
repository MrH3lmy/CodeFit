package com.codefit.service;

import com.codefit.model.ComplexityClass;
import com.codefit.model.FinalCategory;
import com.codefit.model.SolvedWith;

/**
 * Every post-solve reflection field a learner can record for a problem (#146), bundled into one
 * value object so {@code ProblemProgressService#updateReflection} doesn't need a 13-parameter
 * method. Every field is optional; passing {@code null} (or {@code false} for the booleans) simply
 * leaves that field unset — see {@link ProblemProgressService#updateReflection} for how partial
 * updates are applied.
 */
public record ProblemReflection(Integer perceivedDifficultyRating, SolvedWith solvedWith, FinalCategory finalCategory,
                                 String approachNotes, String mistakeNotes, String importantObservation,
                                 ComplexityClass timeComplexity, ComplexityClass spaceComplexity, String lessonLearned,
                                 String actualTopic, boolean editorialUnderstood, boolean otherSolutionsReviewed,
                                 boolean simplerImplementationConsidered, boolean betterComplexityConsidered) {
}
