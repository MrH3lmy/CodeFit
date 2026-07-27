# Submissions, outcomes, and post-solve reflection (#146)

This issue splits `ProblemProgress` updates into two independent write paths and adds a way to record
a problem as solved elsewhere without running a new timed workspace session.

## `updateProgress` vs. `updateReflection`

`ProblemProgressService` exposes two methods where #142/#145 originally had one:

- `updateProgress(problemId, state, completedAt)` — **workflow only**. Called exclusively by
  `ProblemSolvingWorkspaceService.finish` when a session ends, it touches `state` and `completed_at`
  and nothing else.
- `updateReflection(problemId, ProblemReflection)` — **everything self-reported**: perceived difficulty
  (now a free 1-10 rating, not the `EASY`/`MEDIUM`/`HARD` enum used for curriculum-suggested
  difficulty), `solvedWith`, `finalCategory`, approach/mistake notes, an important observation, time
  and space complexity (`ComplexityClass`), a lesson learned, the actual topic used, and four
  after-AC checkboxes (editorial understood, other solutions reviewed, simpler implementation
  considered, better complexity considered).

Each method is proven independent of the other in `ProblemProgressServiceTest`: updating progress
never touches a reflection field, and updating reflection never touches `state` or `completed_at`.
This satisfies the issue's requirement that submission/outcome tracking stay clearly separate from a
learner's editable-any-time self-reflection.

`ComplexityClass` is a plain enum (not free text) so time/space complexity stays analytics-friendly;
`ProblemSolvingWorkspaceController.displayComplexity` formats each constant for display (e.g.
`O_N_LOG_N` → `"O(n log n)"`).

## Marking a problem previously solved

`ProblemSolvingWorkspaceService.markPreviouslySolved(problemId, notes)` covers the case where a
learner already knew the solution before using CodeFit and doesn't want to run a fresh timed session
just to record that. It reuses the existing `finish` path — `finish(problemId, SUBMITTED, ACX, notes)`
— which already tolerates a session with zero elapsed time (a session is auto-created via
`startOrResume` if none exists), so no new timing logic was needed. It then calls `updateReflection`
with `solvedWith = PREVIOUSLY_SOLVED`, preserving every other reflection field already on record.

Like every other `finish` outcome, this records a new, gapless-numbered `ProblemAttempt` — it never
overwrites or removes earlier attempts on the same problem.

## First-submission accuracy

`ProblemAttemptService.isFirstSubmissionAccurate(problemId)` reports whether attempt number 1 for a
problem was itself `AC` or `ACX` — a coaching signal for #147's dashboards ("did the learner get it
right the first time, or only after iterating"). It returns `false` both when the first attempt failed
and when there are no attempts yet.

## UI

The Solving Workspace screen's new "Post-Solve Reflection" panel (below the existing Finish panel)
lets the learner set every reflection field and save it independently of finishing the session — MenuButtons
for the enum-backed fields (difficulty rating, solved-with, final category, time/space complexity),
free-text fields for topic/notes/observation/lesson, checkboxes for the four after-AC questions, and a
"Mark Previously Solved (ACX)…" button next to the existing Finish actions.

## Known limitations

- The reflection panel is a flat form, not conditionally shown only after a problem reaches `SOLVED`;
  a learner can fill it in at any point, which the issue's "editable any time" requirement explicitly
  allows.
- Coaching-facing surfacing of these fields (e.g. dashboards, trends over time) is #147's scope; this
  issue only makes the data capturable and independently editable.
