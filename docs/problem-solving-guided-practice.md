# Guided curriculum practice loop (#161)

After an import, the Problems screen (#144) opens directly onto a "Today" panel instead of a bare
list of hundreds of problems: current stage/set, mandatory progress, today's target vs. how many
problems were solved today, the most recent solving bottleneck, and a one-click "Start Today's
Practice" button that opens the recommended problem straight in the Solving Workspace (#145).

## Architecture

`GuidedPracticeService` is a thin composition layer over services that already existed and are
already tested — it introduces no new aggregation logic of its own:

- `ProblemDashboardService` (#147) supplies current stage/set and mandatory totals (`CoreProgress`)
  and the recent bottleneck phase (`TimingInsights.bottleneckPhase`).
- `ProblemLibraryService` (#144) supplies the recommended problem and the revisit queue.
- The only genuinely new pieces are the daily target preference and "how many problems were solved
  today", both owned by `GuidedPracticeService` itself.

This means the Today panel can never show a different stage/set/recommendation than the Problem
Library or Dashboard screens do — they're reading the same aggregation, not a second copy of it.

## Mandatory-gated recommendation

`ProblemLibraryService.getNextRecommendedProblem()` now prefers mandatory work: while any mandatory
roadmap position remains unsolved, it is always the recommendation — never a later-stage or optional
position — satisfying "do not introduce a later-stage problem while required earlier work remains."
Once every mandatory position is solved, it falls through to the first unsolved position overall
(mandatory or optional), so optional work doesn't stall the roadmap forever.

The "unless the learner explicitly overrides" half of that requirement doesn't need its own UI or
parameter: every row in the Problem Library already has its own direct **Start / Resume** button (see
`ProblemsController`), so a learner can always start any specific problem — mandatory, optional, or
out of order — regardless of what the guided recommendation currently says. The override mechanism
already existed; only the default needed to change.

The gating logic itself (`ProblemLibraryService.selectNextRecommended`) is a package-private static
method over a plain list, mirroring `ProblemDashboardService`'s convention, so it's unit tested
directly (`ProblemLibraryServiceTest`) without the shared local test database's cross-test noise.

## Revisit queue

`ProblemLibraryService.getRevisitQueue()` lists every roadmap position currently `NEEDS_REVISIT`
("Could Not Solve" in the Solving Workspace), in Blind Order. It's a separate list from the main
frontier/recommendation — working through it never reorders or otherwise disturbs the roadmap
sequence the guided recommendation tracks. The Today panel shows a "Practice Revisit Queue (n)" button
that opens the first queued problem directly (the same override mechanism as any other row).

## Daily target

A `daily_target_problems` column (default 3, additive migration) on the same `user_progress` singleton
row every other CodeFit preference already lives on (theme, guided-routine session length, solving
checkpoints, etc.) — `GuidedPracticeService#getDailyTargetProblems`/`#setDailyTargetProblems`. "Solved
today" counts `ProblemProgress` rows with state `SOLVED` and a `completedAt` timestamp on the current
date; this counting logic is a package-private static method
(`GuidedPracticeService#countSolvedOn`) tested directly against hand-built data, for the same
shared-database reason `selectNextRecommended` is.

## Session pause/resume and per-attempt next actions

Both were already fully delivered by earlier work and needed no changes here:

- **Pause/resume across restarts**: `ProblemSolvingSession` (#142/#145) already persists per-phase
  timers and pause state; "Start Today's Practice" calls the same
  `ProblemSolvingSessionService#startOrResume` every other Start/Resume action uses.
- **Post-attempt next action** (continue, review a hint, mark for revisit, reflect, move on):
  `ProblemSolvingWorkspaceService#finish`'s four outcomes (#145/#146) already cover
  continue-implicitly/mark-for-revisit (`COULD_NOT_SOLVE`)/reflect
  (`ProblemProgressService#updateReflection`)/move-on (the next call to
  `getNextRecommendedProblem` reflects the just-finished attempt immediately, since nothing here is
  cached).

## Known limitations

- The daily target is measured in problems, not minutes, since `ProblemAttempt`'s timings are
  per-phase rather than aggregated per day; a minutes-based target would need a new daily rollup this
  issue didn't require building.
- "Recent bottleneck" reuses the whole-history bottleneck phase from `ProblemDashboardService`, not a
  separately time-windowed "recent" figure — the issue's own dashboard equivalent has the same
  characteristic (see `problem-solving-dashboards.md`'s "known limitations").
