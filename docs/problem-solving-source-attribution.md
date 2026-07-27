# Source attribution, licensing, and safe packaging for imported training roadmaps (#149)

CodeFit's problem-solving roadmap is built from a workbook the learner imports locally — most
commonly a Junior Training Sheet-style spreadsheet whose ordering, annotations, and curated links are
someone else's curated curriculum, not CodeFit's own content. This document is the policy this repo
follows so that support is never confused with redistribution.

## What stays out of the repository

- **No third-party workbook, or any dataset extracted from one, is ever committed to this repository**
  — not the original `.xlsx`, not a JSON/CSV export of its rows, not a "sample" trimmed down from real
  content. Every importer test fixture (`TrainingSheetFixtures`) is built programmatically from
  made-up problems and titles; `WorkbookContentPolicyTest` enforces this in CI by scanning the
  repository for any `.xlsx` file and for the real Junior Training Sheet's known authorship, failing
  the build if either ever appears.
- CodeFit's own bundled content (the flashcard decks/paths shipped with the app, e.g. the Java Backend
  and Database Internals learning paths) is unaffected by any of this — it was authored for CodeFit and
  is licensed under this repository's own license. This policy is specifically about *imported*
  problem-solving roadmaps, which are the learner's own local data.

## What CodeFit stores after import

An import creates or updates, entirely in the learner's local SQLite database:

- **`problems`** — the catalog identity (platform, code, title, URL, topic, quality rating, learning
  resource links) of each referenced problem.
- **`roadmap_entries`** — which roadmap stage/position/set each problem occupies, and whether it's
  mandatory.
- **`problem_progress`** — the learner's own current state for each problem (imported status becomes
  an initial `ProblemState`; it is never downgraded or blanked by a later re-import).
- **`import_batches`** — one row per import run recording the source attribution described below.

Nothing here is ever transmitted anywhere; it lives in the same local database file as every other
CodeFit table.

## Source attribution

Each import records, on an `import_batches` row:

| Field | Required? | Notes |
|---|---|---|
| Source name | No — falls back to the workbook's file name | e.g. "Junior Training Sheet v3" |
| Source URL | No | Where the workbook came from, if anywhere public |
| Author | No | Who curated it |
| Version | No | Whatever version label the source uses |
| Import date | Always recorded automatically | When this import ran |

Every `roadmap_entries` row created or last touched by an import is stamped with that batch's id
(`RoadmapEntry.getImportBatchId()`), so a learner can always trace a roadmap position back to which
import produced it. See [`docs/problem-solving-domain-model.md`](problem-solving-domain-model.md) for
the underlying schema.

## Imported links, comments, and resources: display yes, export policy

Fields like a problem's `url` and `learningResources` (editorial/article/video links pulled from the
workbook) are displayed locally in the Problems screen and Solving Workspace exactly as the learner's
own local reference material — this is no different from a browser bookmark. CodeFit does not
currently have a bulk "export my roadmap" feature; if one is added later, it must draw a clear line
between **CodeFit's own data model** (which is fine to export/share, e.g. `problem_progress` summary
stats with no imported text) and **imported third-party text** (problem titles, URLs, resource links,
any workbook-authored annotations), which must never be bundled into an export artifact intended for
redistribution without the same permission check this document already requires for import.

## Deleting an imported roadmap

**Settings → Problem-Solving Training → Imported Roadmaps** lists every import batch and offers
"Delete Roadmap…" for each. `TrainingSheetImportService.deleteImportBatch(batchId)`:

1. Deletes every `roadmap_entries` row stamped with that batch id.
2. Deletes the `import_batches` row itself.

Nothing else is touched. This is safe by construction, not by convention: `problem_progress`,
`problem_attempts`, and `flashcards` never reference `roadmap_entries` at all — only `problems`
directly — so deleting a roadmap's positions cannot cascade into a learner's solved/in-progress
records, attempt history, or any flashcard created from a reflection (#148). The underlying `problems`
catalog rows are deliberately left in place too, since another import batch (or a manually-added
roadmap position) may still reference them; they simply become browsable-by-topic-only if nothing
references them in the roadmap anymore.

See `ImportSourceAttributionTest` for the tests proving this isolation directly (recording an attempt,
progress update, and linked flashcard, deleting the import batch, then asserting all three survive
unchanged).

## Product behavior in the app

- The Settings screen's import panel states plainly, before the file picker opens, that the workbook
  never leaves the machine and that the learner is responsible for having permission to use whatever
  they import.
- The optional source-attribution fields are shown right next to the import button, not buried in a
  separate settings page.

## Checklist for approving a future officially bundled curriculum

If CodeFit ever wants to ship a curriculum roadmap bundled with the app (rather than learner-imported),
every item below must be checked off and recorded (e.g. in the PR that adds it) before it merges:

- [ ] Written permission from the curriculum's author/rights-holder to redistribute it as part of
      CodeFit, or a license that explicitly permits redistribution and modification (e.g. a permissive
      open license), confirmed in writing/email/issue comment and linked from the PR.
- [ ] The exact scope of what's being bundled is enumerated (problem list/ordering only? annotations?
      editorial text? curated links?) — attribution requirements can differ per piece.
- [ ] A `LICENSE`-adjacent attribution file (or a clearly marked section of this repository's
      documentation) names the source, author, and license terms, matching however the source's
      license requires attribution to be given.
- [ ] The bundled data is added the same way CodeFit's own bundled decks are (a seed/install path like
      `DatabaseInternalsPackService`, not a raw copy of someone's spreadsheet), so it goes through the
      same review, testing, and idempotency bar as every other piece of shipped content.
- [ ] A maintainer with authority to accept third-party content on behalf of the project signs off in
      the PR, separate from whoever implemented the integration.

Until every box above is checked for a specific curriculum, roadmap content stays exactly as it is
today: something the learner imports locally, never something CodeFit bundles or redistributes.
