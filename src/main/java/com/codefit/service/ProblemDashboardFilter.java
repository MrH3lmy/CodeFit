package com.codefit.service;

import com.codefit.model.RoadmapStage;

import java.time.LocalDate;

/**
 * The optional stage/date-range lens over {@link ProblemDashboardService}'s metrics (#147). Every
 * field is optional ({@code null} means "no constraint"), mirroring {@link ProblemLibraryFilter}.
 *
 * <p>{@code stage} narrows which roadmap-linked problems feed quality and topic metrics to just that
 * stage; {@code fromDate}/{@code toDate} (inclusive) narrow which {@link com.codefit.model.ProblemAttempt}
 * rows feed timing and first-submission metrics to attempts submitted in that range. Core progress
 * metrics (stage breakdown, status breakdown, current stage/set) intentionally ignore this filter —
 * they are already a whole-roadmap, all-stage view by definition.
 */
public record ProblemDashboardFilter(RoadmapStage stage, LocalDate fromDate, LocalDate toDate) {

    public static ProblemDashboardFilter empty() {
        return new ProblemDashboardFilter(null, null, null);
    }

    public boolean isEmpty() {
        return equals(empty());
    }

    public ProblemDashboardFilter withStage(RoadmapStage stage) {
        return new ProblemDashboardFilter(stage, fromDate, toDate);
    }

    public ProblemDashboardFilter withFromDate(LocalDate fromDate) {
        return new ProblemDashboardFilter(stage, fromDate, toDate);
    }

    public ProblemDashboardFilter withToDate(LocalDate toDate) {
        return new ProblemDashboardFilter(stage, fromDate, toDate);
    }
}
