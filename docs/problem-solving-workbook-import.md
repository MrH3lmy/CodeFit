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
  `TrainingSheetColumns`, case-insensitively, with embedded line breaks normalized to spaces, and
  under several common aliases (e.g. "Link" or "URL" both mean the problem link; "Required" or
  "Mandatory" both mean the mandatory flag). See that class for the full alias list. Two headers are
  matched structurally rather than by literal alias — a compound "&lt;something&gt; Category" column
  and its paired "Category Code" column — since a real workbook's authors may qualify a
  classification column with their own name, which this importer must never hard-code (see
  `WorkbookContentPolicyTest`).

### The real Junior Training Sheet (#159)

The approved real-workbook fixture (`docs/junior-training-sheet-fixture.md`) exposed shapes a
purely alias-based reader couldn't handle:

- **A missing title header.** Every roadmap sheet except one spells out a title header in the
  column immediately before the problem-code column; that one sheet leaves it blank. If a header row
  recognizes a code column but no title column, `TrainingSheetWorkbookReader` assumes the column
  immediately to its left is the title column anyway, rather than rejecting the sheet.
- **Embedded hyperlinks instead of a URL column.** The workbook has no explicit URL column at all —
  the judge link lives on the code (or title) cell itself, either as a native Excel hyperlink or as
  the URL argument of a `=HYPERLINK(url, "text")` formula. The reader recovers whichever is present;
  POI's formula evaluator resolves a `HYPERLINK()` formula to its display text even when the
  workbook has no cached value for that cell, so the same code path handles both cases.
- **No platform column.** Platform is inferred from the code's prefix (e.g. `CF` → Codeforces,
  `UVA` → UVA) and, failing that, from the recovered URL's host — see `PlatformInference`. A
  code/URL neither recognizes falls back to the workbook's generic default platform rather than
  guessing.
- **Instructional, aggregate, and sample rows interleaved with real data.** An "AC Averages =&gt;"
  summary row and a handful of literal "Sample Name"/"Sample Link" placeholder rows are dropped by
  the reader before the importer ever sees them (see `TrainingSheetWorkbookReader`), the same way an
  entirely blank spacer row is — they are never counted as invalid rows, since they were never
  candidate problems to begin with.
- **Aggregate performance data per row**: submission count, four phase timings (minutes in the
  workbook, stored in seconds), a 1-10 perceived difficulty, an independence flag ("By yourself?
  Yes/No/Hint"), and a short approach note. The importer turns these into one `ProblemAttempt`
  "snapshot" and a set of `ProblemProgress` reflection fields per problem — see "Attempts and
  reflection data" below.
- **A cell formula POI's evaluator can't compute** (e.g. an aggregate row using a function outside
  POI's supported set) no longer fails the whole read; the reader falls back to that cell's
  last-saved value, or blank, rather than raising `WorkbookImportException` for a row that was never
  going to be treated as real problem data anyway.

`RealJuniorTrainingSheetImportTest` imports the approved fixture directly and asserts the product's
exact expected counts (926 roadmap memberships, 923 unique problems, all 172 Stage B problems) as a
standing regression test, in addition to the synthetic-fixture coverage in
`TrainingSheetImportServiceTest`.

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

## Attempts and reflection data (#159)

When a row's status column resolves to a judge verdict (`AC`, `WA`, `TLE`, etc. — matching
`SubmissionResult`), the importer creates **at most one** `ProblemAttempt` "snapshot" per problem:
the workbook describes aggregate per-problem statistics (a submission count, four phase timings), not
a per-submission log, so this is one attempt representing that aggregate rather than a fabricated
sequence of individual submissions. Its `attemptNumber` is the workbook's own submit count when
present (so the count itself isn't lost), phase timings convert from the workbook's minutes to the
model's seconds, and its notes come from the "1-2 line comments about your approach" column.

This only ever happens the first time a problem is imported (guarded by
`ProblemAttemptRepository#countByProblemId`): a re-import, or the same problem appearing in a second
roadmap stage, never adds a second attempt or touches an attempt the learner already recorded live
through the app.

Alongside progress state, `ProblemProgressService#applyImportedReflection` fills in perceived
difficulty (the "Problem Level /10" column), assistance level (the "By yourself?" column: `Yes` →
`SELF`, `Hint` → `HINT`, `No` → `EDITORIAL` — the workbook doesn't distinguish "read the editorial"
from "copied a full solution", so `No` maps to the more common of the two), actual topic (the
per-row Category column), and approach notes — one field at a time, and only where that specific
field is still unset, so a learner's own already-recorded value is never overwritten.

## Known limitations

- The workbook's status column is matched against a small set of common tokens (`AC`, `Accepted`,
  `In Progress`, `Revisit`, etc. — see `TrainingSheetImportService`'s alias maps); an unrecognized,
  non-blank status is reported as a warning rather than silently ignored.
- Solving sessions (`problem_solving_sessions`) are never touched by import — those are live,
  resumable in-app state, not something a workbook snapshot should seed.
- The `Topics` sheet's own numeric "Level"/"Quality" columns (present in the real workbook on a
  different scale than the product's 1-5 `qualityRating`) are read from the sheet but not applied to
  `Problem.qualityRating`, to avoid silently misrepresenting the scale; only its classification
  columns (topic/category) are applied.
