# Junior Training Sheet workbook import (#143)

`TrainingSheetImportService` imports a local Junior Training Sheet-style `.xlsx` workbook into the
problem-solving domain model documented in
[`problem-solving-domain-model.md`](problem-solving-domain-model.md). The workbook file never leaves
the machine — it's read from wherever the learner picks it via **Settings → Problem-Solving
Training → Import Training Sheet…** — and the real Junior Training Sheet is never committed to this
repository; every fixture used in the automated tests is a small synthetic workbook built
programmatically at test time.

## Expected workbook shape

- One sheet per roadmap stage, named exactly `A`, `B`, `C1`, `C2`, `D1`, `D2`, `D3` (matching
  `RoadmapStage`). A workbook doesn't need all seven — whichever are present are imported, and the
  rest are reported as missing by `validate()` without failing the import.
- An optional `Topics` sheet: alternative classification metadata over problems the roadmap sheets
  already imported, not a source of new problems in its own right.
- Each roadmap sheet's header row is matched against the canonical columns in
  `TrainingSheetColumns`, case-insensitively and under several common aliases (e.g. "Link" or "URL"
  both mean the problem link; "Required" or "Mandatory" both mean the mandatory flag). See that
  class for the full alias list.

The exact header text of the real Junior Training Sheet wasn't available while building this
importer (the epic's rules keep the real file out of this repository), so the alias list is a
best-effort guess at common spellings. If a real workbook uses a header this importer doesn't
recognize, `validate()` reports that sheet as unusable rather than the importer silently skipping
every row in it.

## Local, transactional, idempotent, repeatable

- **Local**: the workbook path is a plain local `Path`; nothing is uploaded or fetched over the
  network.
- **Transactional**: one call to `importWorkbook` processes every sheet against a single shared
  JDBC connection and commits only if every row completes without a database error. Any failure
  midway rolls back the entire import — there is no partial-import state to clean up.
- **Idempotent and repeatable**: re-importing the same workbook produces zero new problems, zero new
  roadmap memberships, and zero progress changes the second time, because the importer is a thin
  orchestration layer over the invariants `ProblemService` and `ProblemProgressService` already
  enforce (see below) — it never bypasses them to write directly to a repository.
- **Dry-run preview**: `preview(path)` runs the exact same logic as `importWorkbook(path)` but always
  rolls back at the end, so a learner can see the same `TrainingSheetImportSummary` counts a real
  import would produce without committing anything.

## How rows become problems, memberships, and progress

For each present roadmap sheet, every row is processed in order:

1. **Problem identity** — `ProblemService.upsertProblem` is called with the row's code/platform/
   title/etc. A `(platform, externalCode)` match reuses the existing `Problem` (updating its catalog
   fields if they changed); no match creates a new one. This is exactly how "the three repeated
   problem codes are represented as unique problems with multiple memberships" is satisfied: the same
   code appearing in two different roadmap sheets resolves to the same `Problem` both times.
2. **Roadmap membership** — `ProblemService.upsertRoadmapMembership` registers the problem at this
   sheet's stage, using an explicit `Order` column if the sheet has one, or the row's natural
   position in the sheet otherwise. A conflicting position (two different problems claiming the same
   `(stage, sequence)` slot) is caught and reported as an invalid row rather than crashing the whole
   import or silently reassigning the slot.
3. **Progress** — if the row's status column maps to a recognized value (e.g. `AC` → `SOLVED`),
   `ProblemProgressService.applyImportedState` is called. It only ever fills in a state from a still
   `NOT_STARTED` record; if the learner has already recorded any progress at all, the imported value
   is silently left alone. This is what satisfies "do not overwrite newer CodeFit progress with blank
   or older workbook values" — without needing a modification timestamp from the workbook, which the
   source spreadsheet doesn't reliably provide.

A row missing a problem code or title is invalid and skipped. A row whose `(platform, externalCode)`
key repeats within the same sheet (e.g. an accidental copy-paste) is a duplicate and skipped, counted
separately from invalid rows.

The `Topics` sheet is processed last, after every roadmap sheet, so the problems it refers to already
exist. A `Topics` row updates the matching problem's `topic` field; a code with no matching problem is
a warning, never an insert — the Topics sheet is explicitly alternative classification metadata over
existing problems, not a second source of new problems.

## Import summary

`TrainingSheetImportSummary` reports, for either a real import or a preview: problems created,
problems updated (catalog fields actually changed on an existing problem), existing problems reused
(matched by natural key, whether or not anything changed), roadmap memberships created, progress
records imported, duplicate rows skipped, invalid rows, and a list of human-readable warnings (e.g.
an unrecognized level/quality value, a `Topics` row with no matching problem, a roadmap slot
conflict).

## Connection-scoped overloads

Running an entire import as one transaction — and letting a dry-run roll back cleanly — required
`ProblemRepository`, `RoadmapEntryRepository`, and `ProblemProgressRepository` to expose a
`Connection`-scoped overload of each operation the importer needs, alongside the original
convenience method that opens its own connection. `ProblemService`/`ProblemProgressService` got the
equivalent additive overloads (`upsertProblem`, `upsertRoadmapMembership`, `applyImportedState`),
returning richer result types (`created`/`fieldsUpdated`/`applied` flags) the importer needs for its
summary counts. Every existing public method kept its original signature and behavior — this was a
purely additive change from #142.

## Known limitations

- The workbook's status column is matched against a small set of common tokens (`AC`, `Accepted`,
  `In Progress`, `Revisit`, etc. — see `TrainingSheetImportService`'s alias maps); an unrecognized,
  non-blank status is reported as a warning rather than silently ignored.
- The importer only ever sets progress state, never `perceivedDifficulty`, `solvedWith`,
  `finalCategory`, or notes — the workbook doesn't carry that information, and those fields are
  populated later through the in-app workflow (#145/#146).
- Attempts (`problem_attempts`) and solving sessions (`problem_solving_sessions`) are never touched by
  import; the workbook only describes the roadmap and a coarse current status, not submission
  history.
