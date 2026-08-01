# Progressive hint ladder and idea explanations (#162)

The Solving Workspace's "Hints" panel lets a learner unlock up to four increasingly explicit levels
one at a time — Clarify, Observation, Approach, Explanation — rather than jumping straight to a full
solution, and records how far up the ladder they went so that depth feeds the assistance-level
calculation.

## Domain model

`ProblemGuidance` (one row per `Problem`, `UNIQUE(problem_id)`, entirely separate from problem identity
and learner progress) holds the four levels' text (Explanation split across `explanation_text`,
`pseudocode_text`, `complexity_notes`, and `common_mistakes_text` — see below), optional prerequisite
topics, and reference links.
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

## Assistance-level calculation, and how it reaches independence metrics

`ProblemGuidanceService#computeAssistanceLevel(HintLevel maxOpened)` maps hint depth to a `SolvedWith`
value: no hint opened → `SELF`; any of the first three levels (still teaching reasoning, not handing
over the answer) → `HINT`; opening the full `EXPLANATION` → `EDITORIAL`, since that level's own
content already *is* CodeFit's editorial-equivalent explanation. `SolvedWith.SOLUTION` is deliberately
never returned by this computation — none of the four hint levels are "here is a ready-made solution
to copy", so that distinction stays a manual, learner-chosen reflection value.

`ProblemSolvingWorkspaceService#finish` now captures the just-finished attempt's
`highestHintLevelOpened` before `ProblemSolvingSessionService#reset` deletes the session row, and — on
every successful completion — writes the equivalent assistance level onto `ProblemProgress.solvedWith`
via `applyAssistanceForSuccessfulCompletion`. This is what makes "opening a hint is persisted and
reflected in independence metrics" literally true: the dashboard's independence numbers read
`solvedWith`, and previously had no way to see hint depth at all. This wiring, and the matching
"hint-dependent solves stay `SOLVED` but are still scheduled in the revisit queue" behavior in
`ProblemLibraryService`, were completed alongside this change (see
`GuidedCurriculumFlowRegressionTest`).

## The full Explanation's four required parts

The Explanation level must cover idea/reasoning, pseudocode, complexity, and common mistakes. These
are four distinct `problem_guidance` columns (`explanation_text`, `pseudocode_text`,
`complexity_notes`, `common_mistakes_text`) rather than one field a learner has to remember to pack
everything into. `ProblemGuidance#textForLevel(EXPLANATION)` composes them into one labeled block for
display; a part that hasn't been authored yet says so explicitly ("(not yet authored)") instead of
being silently dropped, since dropping it would misleadingly read as "there are no common mistakes"
rather than "nobody has written this part yet." `ProblemGuidance#hasCompleteExplanation()` reports
whether all four are present, for anything that wants to distinguish a fully-authored Explanation from
a partial one. The workspace's "Edit Guidance" panel has a text area for each of the four parts.

## Provenance in the UI

`GuidanceSource` was previously stored and tested but never actually shown to a learner reading the
guidance. The Hints panel now displays "Source: <Learner|Codefit|Imported|Provider>" alongside
prerequisites/references whenever a guidance row exists, sourced directly from
`ProblemGuidance#getSource()` — the same value `ProblemGuidanceService#saveGuidance` already recorded.

## Prerequisites and flashcards

Prerequisites are shown as a plain comma-separated list above the hint ladder when present ("Show
prerequisites for the current idea when available"). Creating flashcards from key observations and
mistakes was already delivered by `ProblemFlashcardService` (#148) and needed no changes here — the
Solving Workspace's existing "Create Flashcard" panel (sourced from post-solve reflection fields like
`importantObservation`/`mistakeNotes`) already covers it. This reuses the learner's own reflection
notes rather than the hint ladder's authored `observationText`/explanation content directly; turning a
hint's own text into a flashcard source is not covered here.

## Initial content scope

This delivers the mechanism for every imported problem. Authoring high-quality guidance for a pilot
set at the start of Stage A (per the issue's stated initial scope) was completed as a follow-up
content task (#171): see `StageAPilotGuidanceSeed` for the documented ten-problem pilot set (the
earliest real rows on the workbook's "A" sheet, identified by the same stable `(platform,
externalCode)` pairs the real-workbook import tests already use) and its full four-level,
CodeFit-authored guidance. `DatabaseConfig#seedStageAPilotGuidance` seeds each pilot problem's
catalog row, Stage A roadmap slot, and `CODEFIT`-sourced guidance idempotently on every startup
(`INSERT OR IGNORE` against each table's own `UNIQUE` constraint), using the exact identity and
sequence order a real import of the approved workbook assigns to the same rows — so importing that
workbook later merges into these same rows rather than duplicating them, and the guidance stays
attached either way. Every problem beyond this pilot set still shows "no guidance authored yet" until
a human authors it through the workspace's Edit Guidance panel or `saveGuidance` directly.

## Known limitations

- Only the ten-problem Stage A pilot set (#171) has seeded guidance; every other imported problem
  still shows "no guidance authored yet" until a human authors it through the workspace's Edit
  Guidance panel or `saveGuidance` directly. Scaling content coverage beyond the pilot remains future
  work.
- Guidance editing in the workspace covers the seven text fields (four ladder levels plus the three
  Explanation sub-parts) only; editing prerequisites/reference links is available through
  `ProblemGuidanceService#saveGuidance` directly (and covered by its tests) but has no dedicated
  workspace UI yet.
