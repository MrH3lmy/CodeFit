# Progressive hint ladder and idea explanations (#162)

The Solving Workspace's "Hints" panel lets a learner unlock up to four increasingly explicit levels
one at a time — Clarify, Observation, Approach, Explanation — rather than jumping straight to a full
solution, and records how far up the ladder they went so that depth feeds the assistance-level
calculation.

## Domain model

`ProblemGuidance` (one row per `Problem`, `UNIQUE(problem_id)`, entirely separate from problem identity
and learner progress) holds the four levels' text, optional prerequisite topics, and reference links.
`GuidanceSource` records provenance — `LEARNER`, `CODEFIT`, `IMPORTED`, or `PROVIDER` (reserved for a
future integration, unused today) — so the product always knows whose words a given row's text is.
CodeFit never scrapes or bundles copyrighted third-party editorial text into this table; imported
reference links may point to third-party editorial/video content, but the stored text itself is not
copied from it.

## Tracking the highest level opened

`ProblemSolvingSession` (#142/#145) gained a nullable `highestHintLevelOpened` column. Living there
rather than on `ProblemProgress` means it resets for free the moment a new attempt starts: finishing a
session deletes that row (see `ProblemSolvingSessionService#reset`), so the next attempt's session is
a fresh row with no hint level recorded — no separate reset bookkeeping needed. This is what "track
the highest hint level opened per attempt" means concretely: per *session*, and a session is exactly
one attempt's live state.

## Hint ladder mechanics

`ProblemGuidanceService#openNextHintLevel` always advances exactly one level past whatever is
currently the highest opened (`CLARIFY` if none yet), never lets a caller skip ahead, and is a
harmless no-op once already at `EXPLANATION`. `HintLevel#next()` returns `null` at the top of the
ladder rather than wrapping or throwing.

A level with no authored text reveals as "no guidance authored yet for this level"
(`HintReveal.hasContent() == false`) instead of fabricating plausible-sounding content — missing
guidance is always handled clearly, per the issue's explicit requirement.

`revealLevel(problemId, level)` shows a specific level's content without changing what's recorded as
opened, used for two things: re-displaying already-opened levels when the workspace reloads, and
showing the full explanation directly after an Accepted finish (see below) without needing the ladder
state to reflect it — the attempt is over either way, so there's nothing left to "open" for.

## Explanation after AC

`ProblemSolvingWorkspaceController#finish` shows the full explanation (if authored) in a dialog
immediately after an `ACCEPTED` finish outcome, regardless of how far up the ladder the learner
actually got this attempt — once solved, there's no remaining pedagogical reason to keep hiding it
behind further clicks.

## Editing guidance

The workspace's "Edit Guidance" toggle opens four text areas pre-filled with the current level texts;
saving calls `ProblemGuidanceService#saveGuidance`, which edits the single existing row in place
(source `LEARNER`, since that's who's editing through this screen) rather than versioning or
appending — this is the "allow editing/improving local guidance" requirement. `CODEFIT`- or
`IMPORTED`-sourced guidance can be authored the same way through the same method (see
`ProblemGuidanceServiceTest`); the workspace UI itself only ever saves as `LEARNER`.

## Assistance-level calculation

`ProblemGuidanceService#computeAssistanceLevel(HintLevel maxOpened)` maps hint depth to a `SolvedWith`
value: no hint opened → `SELF`; any of the first three levels (still teaching reasoning, not handing
over the answer) → `HINT`; opening the full `EXPLANATION` → `EDITORIAL`, since that level's own
content already *is* CodeFit's editorial-equivalent explanation. `SolvedWith.SOLUTION` is deliberately
never returned by this computation — none of the four hint levels are "here is a ready-made solution
to copy", so that distinction stays a manual, learner-chosen reflection value (`ProblemProgress`'s
existing post-solve reflection form, #146) rather than something the ladder can infer. This is a pure,
directly unit-tested function (`ProblemGuidanceServiceTest`); wiring it as an automatic prefill into
the reflection form's `solvedWith` picker is left for a follow-up rather than silently overriding a
value the learner may have already chosen themselves.

## Prerequisites and flashcards

Prerequisites are shown as a plain comma-separated list above the hint ladder when present ("Show
prerequisites for the current idea when available"). Creating flashcards from key observations and
mistakes was already delivered by `ProblemFlashcardService` (#148) and needed no changes here — the
Solving Workspace's existing "Create Flashcard" panel (sourced from post-solve reflection fields like
`importantObservation`/`mistakeNotes`) already covers it.

## Initial content scope

This delivers the mechanism for every imported problem; authoring high-quality guidance for a pilot
set at the start of Stage A (per the issue's stated initial scope) is a content task for a follow-up,
not something this change fabricates data for.

## Known limitations

- The computed assistance level isn't automatically written onto `ProblemProgress.solvedWith` — see
  above.
- Guidance editing in the workspace covers the four hint texts only; editing prerequisites/reference
  links is available through `ProblemGuidanceService#saveGuidance` directly (and covered by its
  tests) but has no dedicated workspace UI yet.
