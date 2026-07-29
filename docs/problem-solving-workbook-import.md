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

## Import preview screen (#160)

Selecting a workbook in **Settings → Problem-Solving Training → Import Training Sheet…** never
immediately writes anything, and — since a second round of review found the first cut of this still
simulated a preview by writing then rolling back a transaction — **analysis never opens a database
connection at all**. `SettingsController#analyzeWorkbook` drives a three-part pipeline:

```text
ParsedWorkbook
      ↓
TrainingSheetAnalyzer   (pure; no Connection anywhere in this class)
      ↓
AnalyzedTrainingWorkbook
      ├── previewOf(...)      → TrainingSheetImportSummary, read-only, no DB access
      └── importAnalyzed(...) → the only method that opens a transaction
```

1. **Analyze.** `TrainingSheetImportService#analyze` reads the file and hands it to
   `TrainingSheetAnalyzer`, which normalizes, deduplicates, validates, and diagnoses every row entirely
   in memory, producing an `AnalyzedTrainingWorkbook` (its problems, roadmap memberships, content
   counts, and diagnostics — see `TrainingSheetAnalyzerTest` for direct
   proof no database write ever happens here). This runs on `BackgroundImportExecutor`'s shared,
   **non-daemon** worker thread, not the JavaFX Application Thread — the real workbook has ~926 rows
   across seven sheets — via a `javafx.concurrent.Task`, whose `setOnFailed` handler (not a hand-rolled
   try/catch) guarantees the busy state clears even if something unexpected throws. The button is
   disabled and a status label shows "Analyzing…" while this runs.
2. **Review and confirm.** The *exact* `AnalyzedTrainingWorkbook` from step 1 — never a fresh
   re-parse — is rendered into a structured dialog: summary cards (detected profile/version, unique
   problems, roadmap memberships, blocking/warning counts), a stage table (all seven stages, each with
   detected/valid/skipped row counts — not just a membership count, since instructional/duplicate/invalid
   rows can make "detected" and "valid" very different numbers), a progress summary (solved/in
   progress/needs revisit/not started), a metadata summary (hyperlinks, explicit/inferred/unknown
   platform coverage, topic/level/quality/assistance coverage, and workbook-content attempt/reflection
   coverage), a severity/sheet/row/column/reason diagnostics table, and a plain-text report (via
   `WorkbookPreviewReportFormatter`) behind "Copy Report". "Import Now" is disabled exactly when
   `AnalyzedTrainingWorkbook#hasBlockingDiagnostics()` is true; warning-only diagnostics never disable
   it. Cancelling (or closing the dialog any other way) leaves the database untouched, since nothing
   was ever written.
3. **Transactional import.** Only clicking "Import Now" calls `TrainingSheetImportService#importAnalyzed`
   with that same `AnalyzedTrainingWorkbook` — the source file is **never re-read**. Editing, replacing,
   or even deleting the file after analysis cannot change what gets imported (see
   `TrainingSheetAnalyzerTest#changingTheWorkbookFileAfterAnalysisCannotChangeTheConfirmedImport` /
   `#importDoesNotReReadOrDependOnTheOriginalFileAfterAnalysis`). This also runs on
   `BackgroundImportExecutor`, with the same busy/status treatment and `Task`-based failure handling —
   see "Background execution and application shutdown" below for why its worker thread is non-daemon.

A workbook with nothing importable at all (no usable roadmap sheet) doesn't short-circuit to a bare
error alert: `analyze()` returns an `AnalyzedTrainingWorkbook` with zero content counts and a single
`BLOCKING` `TrainingSheetDiagnostic`, so the review dialog still opens and shows exactly what was and
wasn't recognized (`Recognized sheets` / `Ignored sheets` / `Missing sheets`) — it just keeps "Import
Now" disabled. A real `importAnalyzed` call still refuses outright for such a workbook — there's
nothing valid to write. Only an unreadable/corrupt `.xlsx` file (can't even be parsed) falls back to
the plain error alert, since there's no structured report to show for it at all.

Every other finding (a skipped row, a duplicate code, an unrecognized value, an ignored sheet) is a
`WARNING`-severity `TrainingSheetDiagnostic` carrying the sheet name, 1-based row number when known,
and column name when known (see `TrainingSheetDiagnostic#describe`) — one bad row never blocks the
rest of a 926-row import. A roadmap-slot conflict against a workbook already imported in a *previous*
run can only be discovered once a real connection is open (analysis only sees conflicts within the
workbook being analyzed), so that rarer case is still caught and reported at import time, inside
`importAnalyzed`.

**Stable workbook-content counts vs. database-dependent counts.** `WorkbookPreviewDetails` (shared,
unchanged, by the preview and the import result) reports what the *workbook itself* contains —
`uniqueProblemCount`, `roadmapMembershipCount`, per-stage counts, solved/in-progress/needs-revisit/not-started,
hyperlink and platform-source coverage, topic/level/quality/assistance coverage, and (see below)
detected profile/version and per-stage row accounting — and reads identically whether the workbook has
never been imported or is being re-imported for the tenth time. The approved workbook always previews
**923 unique problems / 926 roadmap memberships / 172 Stage B** — even immediately after it has already
been imported (see
`RealJuniorTrainingSheetImportTest#previewAfterAnAlreadyCompletedImportStillReportsTheSameStableCounts`).
`TrainingSheetImportSummary`'s separate `problemsCreated`/`problemsUpdated`/`problemsReused`/
`roadmapMembershipsCreated`/`attemptsImported`/`reflectionFieldsImported` fields are the only
database-dependent numbers, and they're always `0` on a pure preview (`previewOf`), since a preview
never opens a connection to know what already exists.

**A pure preview never displays those zeros as if they were results.** `problemsCreated == 0` on a
preview means "unevaluated," not "nothing to import" — printing "0 problem(s) created" or "Attempt
snapshots imported: 0" for a workbook that plainly has importable content would be misleading, so
`WorkbookPreviewReportFormatter#format` branches on `TrainingSheetImportSummary#dryRun()`: a preview
shows `Database effect: evaluated only after confirmation` plus the workbook-content counts
`WorkbookPreviewDetails#attemptSnapshotsFound`/`#problemsWithReflectionMetadata` ("N problem(s) in the
workbook", never "imported"); only a completed import shows the actual created/updated/reused/imported
numbers. See `WorkbookPreviewReportFormatterTest`.

**Detected profile/version and per-stage row accounting (#160).** `TrainingSheetWorkbookReader` scans
every cell of every sheet (not just recognized roadmap columns) for a version marker — a cell
mentioning "version" alongside a version-shaped token (the real workbook has an "Info" sheet cell
reading "Currenet Version V7.0"), falling back to a standalone version-shaped cell, and finally to
`"Not detected"` — entirely from the workbook's own content, never the file name and never a
hard-coded author's name. `TrainingSheetAnalyzer` pairs that with a structural profile name:
`"Junior Training Sheet"` when at least one usable roadmap sheet was found, `"Generic training
workbook"` otherwise. Per-stage, `WorkbookPreviewDetails#stageSummaries` reports `detectedRows` (every
row slot below the sheet's header, including entirely blank ones), `validRows` (what became a roadmap
membership), and `skippedRows` (the difference) — a stage membership count alone can't distinguish "this
sheet only has 5 rows" from "this sheet has 900 rows and 895 were instructional/duplicate/invalid". See
`TrainingSheetAnalyzerTest`'s profile/version/row-accounting tests and
`RealJuniorTrainingSheetImportTest`'s assertions against the real fixture.

The import-complete dialog offers a "Go to Problem Library" button
(`NavigationService#showProblems`) so a multi-hundred-problem import doesn't end in a dead-end alert.

`WorkbookPreviewReportFormatter` has no JavaFX dependency, so its output is covered by plain unit
tests (`TrainingSheetImportServiceTest`, `WorkbookPreviewReportFormatterTest`) without needing a UI
toolkit; `RealJuniorTrainingSheetImportTest` additionally asserts that `preview()` and
`importWorkbook()` against the real fixture produce matching `WorkbookPreviewDetails`, and that the
approved workbook has zero blocking diagnostics.

## Background execution and application shutdown (#160)

`BackgroundImportExecutor` is the single, application-owned executor every analyze/import `Task` runs
on — its worker thread is deliberately **non-daemon**: a daemon thread can be killed mid-transaction the
instant the JVM decides to exit, with no warning and no chance to finish or roll back cleanly. An active
database import needs to survive on its own terms, not "as long as the JVM happened to still be
running."

- `BackgroundImportExecutor#markImportActive` brackets only the transactional (write) phase of an
  import — analysis is read-only, bounded, and safe to simply abandon, so it never sets this.
- `NavigationService#setPrimaryStage` registers a close-request handler that checks
  `BackgroundImportExecutor#hasActiveImport()`: if a real import is writing, closing the window asks the
  learner to confirm first (quitting cancels it before anything is saved, since import is one
  all-or-nothing transaction — there's no partial-write risk, just the risk of silently losing an
  in-flight import).
- `CodeFitApplication#stop()` calls `BackgroundImportExecutor#shutdown`, which stops accepting new work
  and waits briefly for anything still running before force-cancelling via `shutdownNow()` — the
  standard graceful-then-forceful `ExecutorService` shutdown contract (see
  `BackgroundImportExecutorTest`, which exercises the shutdown sequence against a disposable executor
  rather than the shared singleton, so a test run doesn't leave the real one unusable for the rest of
  the suite).

## Local, transactional, idempotent, repeatable

- **Local**: the workbook path is a plain local `Path`; nothing is uploaded or fetched over the
  network.
- **Transactional**: one call to `importAnalyzed` (or the `importWorkbook` convenience wrappers that
  call `analyze` then `importAnalyzed`) processes every analyzed problem/membership against a single
  shared JDBC connection and commits only if every row completes without a database error. Any failure
  midway rolls back the entire import — there is no partial-import state to clean up.
- **Idempotent and repeatable**: re-importing the same workbook produces zero new problems, zero new
  roadmap memberships, and zero progress changes the second time, because the importer is a thin
  orchestration layer over the invariants `ProblemService` and `ProblemProgressService` already
  enforce (see below) — it never bypasses them to write directly to a repository.
- **Pure preview, no simulated import**: `preview(path)` (or `previewOf(analyzed)`) never opens a
  database connection at all — it renders `TrainingSheetAnalyzer`'s purely in-memory analysis, not a
  transaction that gets written then rolled back. A learner sees the workbook's own stable content
  counts before anything is written, and importing later never has to "guess how it would have gone"
  from a dry-run database round trip.

## How rows become problems, memberships, and progress

`TrainingSheetAnalyzer` processes every present roadmap sheet's rows in order, entirely in memory, and
produces two lists: `AnalyzedProblem`s (deduplicated by `(platform, externalCode)`) and
`AnalyzedRoadmapMembership`s (one per valid row). `TrainingSheetImportService#importAnalyzed` then
applies exactly those two lists transactionally:

1. **Problem identity** — for each `AnalyzedProblem`, `ProblemService.upsertProblem` is called with its
   (already-deduplicated) code/platform/title/etc. A `(platform, externalCode)` match reuses the
   existing `Problem` (updating its catalog fields if they changed); no match creates a new one. This is
   exactly how "the three repeated problem codes are represented as unique problems with multiple
   memberships" is satisfied: the same code appearing in two different roadmap sheets was already
   collapsed into one `AnalyzedProblem` during analysis, with the *last* sheet's row values winning
   (matching `upsertProblem`'s own "the newest row wins" behavior) — including a later `Topics` sheet
   row overriding the topic again.
2. **Roadmap membership** — for each `AnalyzedRoadmapMembership`, `ProblemService.upsertRoadmapMembership`
   registers the problem at that sheet's stage, using an explicit `Order` column if the sheet has one,
   or the row's natural position otherwise. A position two *different* problems both claim **within the
   same workbook** is caught during analysis itself (no database needed: it's purely about what the
   workbook's own rows say) and reported as an invalid row — the conflicting row still becomes an
   `AnalyzedProblem` (so it's still created, matching the pre-analysis-split behavior), it just never
   becomes a membership. A conflict against a *different*, already-imported workbook can only surface
   once a real connection is open, so `importAnalyzed` still catches and reports that rarer case itself.
3. **Progress** — if a row's status column maps to a recognized value (e.g. `AC` → `SOLVED`), that
   becomes the `AnalyzedProblem`'s `importedState` (first row across every sheet to supply one, per
   problem, wins — see below), and `importAnalyzed` calls `ProblemProgressService.applyImportedState`
   for it. That call only ever fills in a state from a still `NOT_STARTED` record; if the learner has
   already recorded any progress at all, the imported value is silently left alone. This is what
   satisfies "do not overwrite newer CodeFit progress with blank or older workbook values" — without
   needing a modification timestamp from the workbook, which the source spreadsheet doesn't reliably
   provide.

A row missing a problem code or title is invalid and skipped. A row whose `(platform, externalCode)`
key repeats within the same sheet (e.g. an accidental copy-paste) is a duplicate and skipped, counted
separately from invalid rows.

**"First row wins" per problem, computed once during analysis.** The workbook's per-problem aggregate
data — one imported progress state, one `ProblemAttempt` snapshot, and the reflection fields
(perceived difficulty/assistance/actual topic/approach notes) — used to be decided by a live database
check on every row (`applyImportedState`/`applyImportedReflection`'s "only fill an unset field" rule,
and `ProblemAttemptRepository#countByProblemId`'s "only the first attempt" rule). `TrainingSheetAnalyzer`
now computes the same "first row, in workbook order, to supply a value wins" result once per
`AnalyzedProblem` during analysis (see `AnalyzedProblem`'s Javadoc) — `importAnalyzed` still applies the
exact same database-side non-destructive guards when writing it, so a learner's own later, already
recorded progress is still never overwritten.

The `Topics` sheet is analyzed last, after every roadmap sheet, matched purely against this workbook's
own analyzed problems (not a database query) — a `Topics` row updates the matching `AnalyzedProblem`'s
topic; a code with no in-workbook match is a warning, never an insert. Since this all happens before
any database access, a `Topics` row referencing a problem that only exists because of a *different*,
earlier workbook import is reported as unmatched — see "Known limitations".

## Import summary

`TrainingSheetImportSummary` reports, for either a real import or a preview: problems created,
problems updated (catalog fields actually changed on an existing problem), existing problems reused
(matched by natural key, whether or not anything changed), roadmap memberships created, progress
records imported, duplicate rows skipped, invalid rows, and a list of human-readable warnings (e.g.
an unrecognized level/quality value, a `Topics` row with no matching problem, a roadmap slot
conflict).

## Connection-scoped overloads

Running an entire import as one transaction required `ProblemRepository`, `RoadmapEntryRepository`, and
`ProblemProgressRepository` to expose a `Connection`-scoped overload of each operation the importer
needs, alongside the original convenience method that opens its own connection. `ProblemService`/
`ProblemProgressService` got the equivalent additive overloads (`upsertProblem`,
`upsertRoadmapMembership`, `applyImportedState`), returning richer result types
(`created`/`fieldsUpdated`/`applied` flags) the importer needs for its summary counts. Every existing
public method kept its original signature and behavior — this was a purely additive change from #142.
None of these overloads are used by analysis at all (#160) — `TrainingSheetAnalyzer` never touches a
repository or a `Connection`; only `TrainingSheetImportService#importAnalyzed` does.

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
- Every current `TrainingSheetDiagnostic` a real workbook produces during analysis is `WARNING`
  severity; `BLOCKING` is only ever raised for "no usable roadmap sheet at all" (see
  `validateStructure`). A single bad row/column is designed to be skipped-and-reported, not to halt an
  otherwise-importable workbook — so there's currently no per-row condition that disables "Import Now"
  the way the workbook-wide case does. A roadmap-slot conflict against a *different*, already-imported
  workbook is the one diagnostic that can only appear at import time (never in the preview that
  preceded it), since analysis has no database to check against.
- The `Topics` sheet is matched against this workbook's own analyzed problems, not a live database
  query (#160's pure-analysis requirement) — a `Topics` row whose code was only imported by a
  *different* workbook is reported as unmatched, where the previous rollback-based implementation could
  see across imports since it was, in effect, always querying the real (about-to-be-rolled-back)
  database. In practice the `Topics` sheet describes problems from the same workbook's own roadmap
  sheets, so this hasn't been observed to matter for the approved real workbook.
- The background-thread analyze/import flow in `SettingsController` (loading state, busy/status label,
  blocking-disable on the review dialog, the `importBusy` concurrency guard) is exercised directly via
  reflection against the real loaded controller in `SettingsControllerImportBusyStateTest`, and
  `settings.fxml` loads under `FxmlLoadingTest`. There is still no automated end-to-end JavaFX
  interaction test driving a real `FileChooser` selection and clicking through the structured review
  dialog's buttons, since a native file-picker dialog can't be scripted headlessly — that path is
  covered by the service-layer analyze/import tests plus code review, not by a single automated test
  exercising the whole click-through.
- `BackgroundImportExecutor#shutdown`'s force-cancel path (`shutdownNow()`, which interrupts the worker
  thread) is the standard best-effort `ExecutorService` shutdown contract, not a guarantee that a
  blocking JDBC call actually aborts instantly or cleanly — some drivers don't honor thread
  interruption for an in-flight call. In practice, quitting while an import is active is a rare,
  learner-confirmed action (see "Background execution and application shutdown"), not a routine path.
- Platform-source coverage (`explicitPlatformCount`/`inferredPlatformCount`/`unknownPlatformCount`) is
  now counted only for rows actually accepted into a roadmap membership — resolving a row's platform
  (`TrainingSheetAnalyzer#resolvePlatform`, via the side-effect-free `PlatformResolution`) no longer has
  any counting effect on its own, so a within-sheet duplicate or a roadmap-slot-conflict row can never
  inflate these counts the way it previously could. See
  `TrainingSheetAnalyzerTest#aDuplicateRowNeverInflatesThePlatformSourceCounts` /
  `#aRoadmapSlotConflictRowNeverInflatesThePlatformSourceCounts`.
