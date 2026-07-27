package com.codefit.service;

import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvingPhase;

import java.util.List;
import java.util.Optional;

/**
 * The guided curriculum practice loop's "Today" snapshot (#161): what a learner sees the moment they
 * open Problem Solving, and everything "Start today's practice" needs to act on in one click.
 * Composed entirely from existing read-only aggregation ({@link ProblemDashboardService},
 * {@link ProblemLibraryService}) plus the one new piece of state this issue introduces — the daily
 * target and how many problems were solved today — so it can never drift from the Problem Library or
 * the Problem-Solving Dashboard it sits alongside.
 *
 * @param currentStage           the roadmap stage of the current frontier (first unsolved position),
 *                                or {@code null} once the whole roadmap is solved
 * @param currentSet             the current frontier's workbook "set" number, if the workbook recorded one
 * @param mandatoryTotal         mandatory roadmap positions across the whole roadmap
 * @param mandatoryCompleted     of those, how many are solved
 * @param dailyTargetProblems    the learner's own preferred number of problems per day (#161)
 * @param solvedToday            problems marked {@code SOLVED} with a completion timestamp today
 * @param nextRecommended        the next problem "Start today's practice" would open, mandatory-gated
 *                                (see {@link ProblemLibraryService#getNextRecommendedProblem}); empty
 *                                once the whole roadmap is solved
 * @param nextRecommendedReason  a plain-language explanation of why that specific problem is next
 * @param revisitQueue           roadmap positions flagged {@code NEEDS_REVISIT}, in Blind Order
 * @param recentBottleneck       the solving phase (Reading/Thinking/Coding/Debugging) that has
 *                                accumulated the most time recently, or {@code null} with no signal yet
 */
public record TodayPlan(RoadmapStage currentStage, Integer currentSet, int mandatoryTotal, int mandatoryCompleted,
                        int dailyTargetProblems, int solvedToday, Optional<ProblemLibraryEntry> nextRecommended,
                        String nextRecommendedReason, List<ProblemLibraryEntry> revisitQueue,
                        SolvingPhase recentBottleneck) {

    public boolean dailyTargetMet() {
        return solvedToday >= dailyTargetProblems;
    }

    public double mandatoryCompletionPercent() {
        return mandatoryTotal == 0 ? 0.0 : mandatoryCompleted * 100.0 / mandatoryTotal;
    }

    public int mandatoryRemaining() {
        return mandatoryTotal - mandatoryCompleted;
    }

    public boolean hasRevisitWork() {
        return !revisitQueue.isEmpty();
    }
}
