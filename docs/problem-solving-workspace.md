# Structured solving workspace (#145)

The **Solving Workspace** (`ProblemSolvingWorkspaceController` / `problem-solving-workspace.fxml`,
reached via "Start / Resume" on the Problem Library screen) guides one problem-solving session
through the four phases the Junior Training Sheet's study mechanism prescribes — Reading, Thinking,
Coding, Debugging — and records where the learner actually spent their time.

## Timer model

`ProblemSolvingSession` (#142) already held per-phase accumulated seconds and a current phase; #145
adds a `paused` flag so a session resumes in the same paused/running state it was left in, rather
than silently accumulating time while CodeFit was closed.

The UI timer (a one-second JavaFX `Timeline` in `ProblemSolvingWorkspaceController`) is intentionally
**not** timestamp-diffing based. Every tick calls
`ProblemSolvingSessionService.recordElapsedTime(problemId, currentPhase, 1)`, which persists that one
second immediately and is a no-op while paused. This means:

- At most a couple of seconds of timing data can ever be lost to a crash or unclean shutdown — never
  a large timestamp-diff gap from being closed for hours.
- "Paused time is excluded" falls out of the no-op behavior directly, with no separate bookkeeping.
- "Only one phase runs at a time" is automatic: the timer only ever advances whichever phase field
  `session.getPhase()` currently names.
- Switching phases (including switching back to correct an accidental switch) never loses an earlier
  phase's total, because `recordElapsedTime(problemId, newPhase, 0)` moves the "current phase"
  pointer without touching any accumulated counter.
- Resuming after an application restart is just re-reading the same persisted row — verified in
  `ProblemSolvingSessionServiceTest` by constructing a **fresh** `ProblemSolvingSessionService`
  instance (standing in for a restart, since there is no in-memory state to carry over) and confirming
  the reloaded session has the same accumulated time and paused state.

## Coaching checkpoints

`SolvingCheckpointPreferenceService` stores two plain `user_progress` columns —
`solving_checkpoints_enabled` and `solving_checkpoint_minutes` (an ascending comma-separated list,
defaulting to `20,60,120`) — the same way every other CodeFit preference (theme, daily new-card
limit, guided session length) is stored. `findNewlyCrossedCheckpoint(previousTotalSeconds,
currentTotalSeconds)` reports the single lowest threshold crossed in that step, or nothing if
checkpoints are disabled or no threshold was reached; the workspace controller calls it on every tick
and shows a plain, dismissible-by-navigating-away banner (never a blocking dialog) when it returns a
value — checkpoints are reminders only, exactly as the issue requires.

## Finishing a session

`ProblemSolvingWorkspaceService.finish(problemId, outcome, submissionResult, notes)` maps the four
supported finish reasons (`SessionFinishOutcome`) onto a `ProblemAttempt` and a `ProblemProgress`
update:

| Outcome | Submission result recorded | Resulting progress state | Attempt created? |
|---|---|---|---|
| `ACCEPTED` | Always `AC` | `SOLVED` | Yes |
| `SUBMITTED` | The verdict picked in the workspace (defaults to `AC` if none picked) | `SOLVED` if the verdict is `AC`/`ACX`, otherwise `IN_PROGRESS` | Yes |
| `COULD_NOT_SOLVE` | The verdict picked (defaults to `WA`) | `NEEDS_REVISIT` | Yes |
| `ABANDONED` | — | Untouched | **No** |

`SessionFinishOutcome` is a deliberately separate concept from `SubmissionResult` (#142's judge-verdict
enum): a `SUBMITTED` or `COULD_NOT_SOLVE` finish can carry any verdict, not just a successful one, so
conflating the two would have made "submitted but wrong answer" impossible to express. `ProblemAttempt`
gained a nullable `sessionOutcome` column (additive migration) recording which finish reason produced
an attempt created this way; attempts recorded any other way (e.g. the workbook importer) leave it
null.

**Why `ABANDONED` creates no attempt**: the other three outcomes all represent a genuine attempt at
the problem (with a real, if not always successful, verdict); `ABANDONED` explicitly means the learner
left without really engaging. Forcing a fabricated judge verdict onto a non-attempt would misrepresent
the record, so `ABANDONED` only calls `ProblemSolvingSessionService.endSession` (marks the session
inactive, keeps its accumulated time in case this was a mistake and the learner resumes) and touches
nothing else. Every other outcome resets the session afterward instead, so a future re-attempt starts
its own phase timers from zero rather than continuing to accumulate into an already-finalized
attempt's numbers — this is also how "finishing a session creates or updates the related attempt
without losing previous attempts" is satisfied: each finish always creates a new, gapless-numbered
attempt (see `ProblemAttemptService`), never overwrites an earlier one.

## Known limitations

- Coaching-checkpoint reminders are computed against **total** elapsed time across all four phases,
  not per-phase; the issue only asked for total-time checkpoints ("initially around 20, 60, and 120
  minutes").
- Submission verdicts, assistance level (`solvedWith`), final category, and post-solve reflection are
  covered separately in [`problem-solving-submissions-reflection.md`](problem-solving-submissions-reflection.md) (#146).
