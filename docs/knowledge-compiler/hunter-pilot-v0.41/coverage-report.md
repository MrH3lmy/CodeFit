# Five-book Knowledge Coverage Ledger

**Source version:** v0.41  
**Observed at:** 2026-10-23  
**Verification:** `SOURCE_ATTRIBUTED_NOT_INDEPENDENTLY_VERIFIED`  
**Private source bodies:** `NOT_INDEPENDENTLY_VERIFIED`

> Historical source-attributed claims only. The delivery report is not checked in,
> no source-unit inventory was independently enumerated, and unknown distributions
> remain `UNKNOWN`. No stage total is a proxy for another stage.

## Reported baseline claims

| Measure | Reported value | Interpretation |
| --- | ---: | --- |
| Structural units | 504 | Reported inventory count; not independently enumerated |
| Preliminary mappings | 157 | Reported preliminary mapping, not learning completion |
| Unmapped units | 347 | Reported remainder; reconciles to 504 − 157 |
| Effective Java first-pass author notes | 90/90 | Author notes, not review-approved |
| Unique mission blueprint slots | 419 | Unique slots; do not add revision events |
| Deep overlay slots | 360 | Separate stage; per-book distribution unknown |
| Revision events | 424 | **Nonadditive**; replacements, not additional unique slots |
| Independent approvals | 0 | Reported zero |
| Production graders | 0 | Reported zero; grading outcomes remain unknown |
| Hunter production imports | 0 | Reported zero |

## Per-book gap matrix

| Book | Units¹ | Preliminary mapped | First-pass authored | Deep overlays | Mission slots | Review-approved | Locally previewable | Runtime imported | Graded | Learner-validated | Next action |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| OCP Java SE 17 Guide | 26 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 0 | UNKNOWN | 0 | UNKNOWN | UNKNOWN | Enumerate source-unit IDs, reconcile book-specific mapping and authoring counts, then obtain independent review before any runtime or learner claims. |
| Effective Java | 90 | UNKNOWN | 90/90 (reported) | UNKNOWN | UNKNOWN | 0 | UNKNOWN | 0 | UNKNOWN | UNKNOWN | Enumerate source-unit IDs and independently reconcile the 90 first-pass notes; then map, review, and assess each unit without treating author notes as approval. |
| 100 Java Mistakes | 100 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 0 | UNKNOWN | 0 | UNKNOWN | UNKNOWN | Enumerate source-unit IDs, reconcile book-specific mapping and authoring counts, then obtain independent review before any runtime or learner claims. |
| Java Coding Problems | 269 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 0 | UNKNOWN | 0 | UNKNOWN | UNKNOWN | Enumerate source-unit IDs, reconcile book-specific mapping and authoring counts, then obtain independent review before any runtime or learner claims. |
| Complete Coding Interview Guide in Java | 19 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 0 | UNKNOWN | 0 | UNKNOWN | UNKNOWN | Enumerate source-unit IDs, reconcile book-specific mapping and authoring counts, then obtain independent review before any runtime or learner claims. |

¹ Structural counts are historical reported claims, not enumerated unit IDs.

## Stage and preview boundaries

- **preliminaryMappedUnits:** Reported preliminary mappings; per-book distribution is UNKNOWN.
- **firstPassAuthoredUnits:** First-pass author notes only; not review approval or learning completion.
- **deepOverlaySlots:** Reported overlay slots; per-book distribution is UNKNOWN.
- **missionBlueprintSlots:** Unique reported blueprint slots; revision events are not additive.
- **reviewApprovedUnits:** Independent review-approved units; the report records zero approvals.
- **locallyPreviewableUnits:** Book-unit local preview coverage; UNKNOWN and distinct from metadata preview records.
- **runtimeImportedUnits:** Hunter production imports; report records zero.
- **gradedUnits:** Units with production grading evidence; UNKNOWN (zero production graders reported).
- **learnerValidatedUnits:** Units validated by learners; UNKNOWN.

The checked-in Java preview contains 4 author-metadata skill records and 16 metadata stage IDs. These author-preview records are not enumerated book units or additional blueprint slots. Preview loading is not runtime import, grading, content approval, or learner validation.

## Provenance and unreconciled claims

- Source attribution: v0.41 delivery report, as attributed in CodeFit issue #298.
- Prior checkpoint: `NOT_AVAILABLE`; no regression comparison is claimed.
- The delivery report is cited by the issue but is not checked in; reported totals are historical claims, not an independently enumerated inventory.
- The private archive and source bodies are unavailable and are not read by this tool.
- A checksum/readback report is not a CodeFit runtime, semantic accuracy, or content approval.
- Per-book preliminary mapping, deep-overlay, mission, local preview, grading, and learner-validation distributions are unknown.

## Reproducibility

From the repository root, run `python3 scripts/knowledge_coverage.py`. It reads only the checked-in original-only ledger fixture and writes deterministic JSON and Markdown reports alongside that fixture.
