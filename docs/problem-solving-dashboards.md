# Problem-solving progress dashboards and coaching insights (#147)

The Problem-Solving Dashboard (`ProblemDashboardController` / `problem-dashboard.fxml`, reached via
"View Dashboard →" on the Problems screen) turns the stored roadmap/progress/attempt data into
core progress, quality, timing, and topic metrics, plus a recommendation and two "needs attention"
lists — all computed at read time by `ProblemDashboardService`, never as separately persisted
counters, so every figure is exactly as fresh as the last saved attempt or reflection.

## Architecture

`ProblemDashboardService.build(ProblemDashboardFilter)` loads each table once (`RoadmapEntry`,
`Problem`, `ProblemProgress`, `ProblemAttempt` — a handful of queries total, not one per problem) and
does the rest of the aggregation in memory via package-private static methods, mirroring
`StatsService`'s convention: every metric is directly unit tested against plain lists/maps in
`ProblemDashboardServiceTest`, with no database involved, and `ProblemDashboardServiceIntegrationTest`
separately exercises the real read path end-to-end.

## Core progress metrics

- **Current stage/set**: the stage and set number of the first not-yet-solved roadmap entry in blind
  order (the same "frontier" concept `ProblemLibraryService.getNextRecommendedProblem` already used
  for #144's recommendation). `roadmapComplete` is true once no such entry remains.
- **Mandatory/optional totals**: counted at the roadmap-*position* level (a problem occupying two
  stages counts twice here), matching the granularity of the stage breakdown below.
- **Stage breakdown**: one row per `RoadmapStage`, always all seven even with zero entries, so the UI
  never has to special-case a stage that hasn't been imported yet.
- **Status breakdown (AC / ACX / CS / In Progress / Not Started)**: one bucket per *distinct* roadmap
  problem (unlike the position-level totals above). `CS` ("could not solve") is
  `ProblemState.NEEDS_REVISIT`, named after the Solving Workspace's "Could Not Solve" finish action
  that produces it. A `SOLVED` problem is split into `AC` vs `ACX` by the verdict of its most recent
  attempt; a problem imported directly as already-solved with no attempt row at all (see
  `TrainingSheetImportService`) defaults to the `AC` bucket, since there is no recorded verdict to
  distinguish it by.
- **Problems solved per week**: a zero-filled trailing 8-week window (Monday-start), grouped by each
  solved problem's `completedAt`.

## Quality metrics

First-submission accuracy reuses `ProblemAttemptService.isFirstSubmissionAccurate` per problem.
Independent-solve and editorial-dependency rates share one denominator — solved problems with a known
`solvedWith` — so "how often did I solve it myself" and "how often did I need the editorial" are
directly comparable. Every rate-based figure is paired with its sample count and hidden behind a
"not enough data yet" label below `ProblemDashboard.MIN_SAMPLE_SIZE` (3) problems, so a single lucky
or unlucky problem can never look like a trend.

## Timing insights

Averages and the total are computed per phase independently (a phase missing on some attempts doesn't
skew the others' averages). The bottleneck phase is whichever phase accumulated the most total time
across every attempt in scope, ties broken by `SolvingPhase`'s declared order (Reading → Thinking →
Coding → Debugging) — fully deterministic, as the issue requires. Every second here already excludes
paused time, since `ProblemSolvingSession` never accumulates time while paused (#145) and
`ProblemAttempt`'s time fields are copied straight from that session at finish time.

## Topic insights

Problems are grouped by `ProblemProgress.actualTopic` (the learner's own self-reported topic, #146)
when set, falling back to `Problem.topic` (the catalog topic) otherwise — the learner's own read on
what a problem actually turned out to need is treated as more informative than how it was originally
filed. A topic needs at least `MIN_SAMPLE_SIZE` attempted problems to be categorized `STRONG` (≥80%
accuracy) or `WEAK` (<50%) rather than `INSUFFICIENT_SAMPLE`; everything in between is `DEVELOPING`.
Independence percent is reported alongside accuracy but does not feed the category, keeping the
STRONG/DEVELOPING/WEAK rule simple and easy to reason about.

## Recommendation

Wraps `ProblemLibraryService.getNextRecommendedProblem()` (blind order, skips `SOLVED` — which
already absorbs `ACX`, see #144) with a plain-language explanation citing the stage, set, and position
selected, or an explicit "every roadmap problem is already solved" message when there is nothing left.

## Overdue reflections / unfinished attempts

- **Overdue reflections**: `SOLVED` problems with no `solvedWith` recorded yet (the reflection form
  was never filled in), oldest solve first.
- **Unfinished attempts**: `ProblemSolvingSession` rows still `active` (started but neither finished
  nor abandoned — see `ProblemSolvingSessionService.endSession`/`reset`), stalest `lastActiveAt` first.

Both are surfaced as their own lists, never folded into the roadmap recommendation, per the issue's
"surface overdue reflection or unfinished attempts separately" requirement.

## Filters

`ProblemDashboardFilter` supports a `stage` filter and a `fromDate`/`toDate` range, applied where each
is practical rather than uniformly everywhere:

- `stage` narrows which roadmap-linked problems feed quality and topic metrics.
- The date range narrows which attempts feed timing insights (first-submission accuracy intentionally
  ignores it, since "first attempt" is a whole-history fact, not something a date window should be
  able to cut off mid-history).
- Core progress (stage breakdown, status breakdown, current stage/set) intentionally ignores both —
  it is already a whole-roadmap, all-stage view by definition.

## Known limitations

- Weekly solved counts use a fixed trailing 8-week window rather than an arbitrary custom range.
- The dashboard is a single flat screen, not itself broken into sub-tabs; every section is always
  visible (scrollable), which keeps the read path simple at the cost of a longer page.
