package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.RoadmapEntry;

/**
 * One row in the Problem Library (#144): a {@link Problem} paired with its current
 * {@link ProblemProgress} and, when relevant, the specific {@link RoadmapEntry} membership the row
 * represents. {@code roadmapEntry} is present for every row in the Blind Order view (one row per
 * membership) and for the Topics view's primary/earliest-stage membership when the problem has at
 * least one; it is {@code null} for a problem with no roadmap membership at all.
 *
 * <p>Both views are built from exactly the same underlying {@link Problem}/{@link ProblemProgress}
 * data (see {@link ProblemLibraryService}), so switching views never duplicates or diverges from the
 * learner's actual progress.
 */
public record ProblemLibraryEntry(Problem problem, RoadmapEntry roadmapEntry, ProblemProgress progress) {

    static ProblemLibraryEntry of(Problem problem, RoadmapEntry roadmapEntry, ProblemProgress progressOrNull) {
        ProblemProgress progress = progressOrNull != null ? progressOrNull : ProblemProgress.notStarted(problem.getId());
        return new ProblemLibraryEntry(problem, roadmapEntry, progress);
    }
}
