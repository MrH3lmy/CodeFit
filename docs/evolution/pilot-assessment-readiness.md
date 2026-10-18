# Assessment readiness slice (#243)

**Overall technical implementation: NOT READY. Protocol freeze: NOT READY.
Pilot execution: NOT READY / PROHIBITED.** This document records author fixtures and regression
verification, not participant outcomes or independent equivalence approval. See the
[pre-implementation gap matrix](pilot-readiness-gap-analysis.md) for the complete protocol audit.

## Delivered boundary

`AssessmentFormReadiness` compares exact versioned metadata using an explicitly supplied `SkillGraph`.
Its bounded installed inventory reports challenge/rubric/graph IDs, cohort, family/variant, canonical
manifest/rubric SHA-256 and asset IDs/digests. It includes no prompt, source, answer, test input/output,
learner record, clock, export or network operation. A comparison checks exact graph and behavior
membership, role/domain/runner, distinct identities, authored difficulty/effort, required check-kind
multiplicities per behavior and explicit declared invariant bindings. Bindings must resolve to
required non-reflection checks; declarations are recognized only for exact pinned definitions.
Matching metadata is structural preparation, never proof of equal cognitive demand or correctness.

`PilotAssessmentForms.baselineCapacity()` loads a digest-pinned **unpublished** candidate using the
existing `CatalogCodec` and `BundledAssets`. It validates every asset before returning an author
definition. It is absent from `JavaTrainingPack`, the pack index, quest membership, SQLite bindings,
Problems, baseline discovery and navigation. It cannot obtain a presentation, draft, hint, solution,
submission, evaluation credit or evidence through existing application routes. Even a synthetic
submission snapshot is rejected as `UNSUPPORTED` by the installed evaluator before runner discovery.
There is no new grader or installed evaluation adapter.

`JavaChallengeEvaluator.practice()` now independently enforces the existing upper-layer
BASELINE/PRACTICE restriction before runner discovery/compilation. Installed post, transfer and
retention specs are rejected. This closes a confirmed direct-entry gap; ordinary authorized baseline
and practice execution remains available. It does **not** implement a study feedback embargo.

No migration: schema remains **25**. Published v1 assets, graph/mapping versions, evaluator version,
estimation, adaptation, XP, sessions/drafts, evidence, journal and P2P contracts are unchanged.
Readiness inspection has no startup hook or database writes.

## Exact candidate identity and mapping

Author bundle: [readiness-v1/baseline-capacity](../../src/main/resources/evolution/packs/java-debugging-concurrency/readiness-v1/baseline-capacity/challenge.properties).
Original CodeFit content, MIT; no external interview/workbook content.

| Identity | Value |
| --- | --- |
| Form / rubric | `baseline-capacity/v1` / `baseline-capacity.rubric/v1` |
| Candidate family / variant / role | `capacity-reservation` / `baseline-capacity` / `BASELINE` |
| Exact graph | `java-debugging-concurrency/v1` |
| Exact targets | `java.concurrency.atomic-update/v1` behavior 0; `java.concurrency.invariant/v1` behavior 0 |
| Prerequisite | `java.debugging.reproduce/v1` |
| Authored difficulty / effort | 3 / 40 minutes; estimates requiring human review, not timed measurements |
| Manifest SHA-256 | `20e2bdee8d5bd2e4073271448954f3b35d33f11ac2a2feefa065b0d642555f75` |
| Reference SHA-256 | `45d3c250ba4e05f30b1da3dd023d7870df551cd48106cb20d4c892d28fa01ecd` |
| Harness SHA-256 | `2441311bf167112402c58035e55cd55fdfe682f1135d7cda8fbff5744ae33e5d` |

All remaining asset digests and exact check specifications are in the canonical manifest.
Manifest edits require a new reviewed identity before any publication; the loader pins this candidate
snapshot. No existing v1 definition is revised. Existing `ChallengeSpec` requires three hint assets;
these are author/reviewer fixtures, not permission to offer assessment hints.

| Check ID | Required | Both exact behavior-0 targets | Actual author-validation guarantee |
| --- | --- | --- | --- |
| `functional` | Yes | Yes | Finite sequential assertions: positive amounts, duplicate/over-budget rejection without mutation, conservation, missing/repeated release, restoration and ID reuse |
| `operation-boundary` | Yes | Yes | Existing `JavaMechanismVerifier` admits the scalar synchronized form and rejects/does not verify broken or unsupported boundaries; no claim of exhaustive Java semantics |
| `concurrent-observation` | No | Yes | Four coordinated workers reserve a finite budget, join boundedly, then restore it; reports only exercised histories, not universal thread safety |
| `explanation` | No | Yes | Ungraded reflection; no objective verification of reproduction or reasoning |

The trusted harness checks paired getters only after worker joins/quiescently. It does not induce
false failures by treating separately synchronized getters as an atomic snapshot. Logical inputs
avoid wall-clock/timezone dependence. Resource limits are process controls, **not a security sandbox**.

## Inspectable baseline/post equivalence preparation

Installed inventory at the audited base (every form pins graph `java-debugging-concurrency/v1`):

| Form | Pool | Family |
| --- | --- | --- |
| `atomic-boundary-drill/v1` | PRACTICE | `compound-update` |
| `baseline-counter/v1` | BASELINE | `diagnostic-counter` |
| `baseline-localize/v1` | BASELINE | `trace-diagnostic` |
| `baseline-trace/v1` | BASELINE | `trace-diagnostic` |
| `counter-repair-b/v1`, `counter-repair/v1` | PRACTICE | `metric-counter` (related variants) |
| `distinct-id-accumulator/v1` | PRACTICE | `deduplicated-accumulator` |
| `inventory-transfer/v1` | TRANSFER | `inventory-reservation` |
| `lock-order-repair/v1` | PRACTICE | `two-resource-order` |
| `post-expiry/v1` | PILOT_POST | `post-expiry-transition` |
| `post-ledger/v1` | PILOT_POST | `post-ledger-accounting` |
| `queue-retention/v1` | RETENTION | `delayed-queue-accounting` |
| `regression-localize/v1` | PRACTICE | `pagination-boundary` |
| `remaining-capacity/v1` | PRACTICE | `bounded-capacity` |

The installed pools contain 2/6/1/1/2 distinct families for
BASELINE/PRACTICE/TRANSFER/RETENTION/PILOT_POST respectively. They share no family across pools,
but nominal family separation does not establish genuine independent transfer. The candidate is
listed separately in the report, never counted as installed baseline coverage.

| Form | Required mapping | Concrete rejection invariant | Concrete exactly-once/accounting invariant | Remaining review |
| --- | --- | --- | --- | --- |
| Installed `baseline-counter/v1`, `diagnostic-counter` | Atomic behavior 0 only | Not equivalent to post requirements | Increment count only; no mapped invariant behavior | Comparator rejects both post comparisons; existing blocker preserved |
| Unpublished `baseline-capacity/v1`, `capacity-reservation` | Atomic + invariant behavior 0; required functional + controlled checks | Duplicate active job, invalid amount or insufficient free units leaves claim map and free units unchanged | Active claims plus free units conserve 12; release credits once; released IDs reusable | Candidate matches required metadata/declarations for both posts; independent review still absent |
| Installed `post-ledger/v1`, `post-ledger-accounting` | Same two behavior targets and required check kinds | Stale/nonpositive revision leaves map/total unchanged | New revision replaces previous signed absolute contribution; duplicate revision adds nothing | Signed arithmetic/replacement and revision ordering differ from capacity admission/release |
| Installed `post-expiry/v1`, `post-expiry-transition` | Same two behavior targets and required check kinds | Reused ID or invalid deadline leaves lease state unchanged | Inclusive logical-time expiration removes/counts live leases once; release cannot double count | Deadline boundary, irreversible IDs and iteration differ from reusable claims |

These forms share a broad atomic map/accounting pattern intentionally. They are not automatically
independent merely because families have different names. The candidate adds bounded admission,
allocation conservation and reusable job identities; posts require signed revision replacement or
logical-time expiry and never-reused IDs. The installed `remaining-capacity` practice also trains
bounded admission: **potential familiarity/construct overlap is an explicit review concern**, not
proof this candidate is an unseen equivalent baseline. #245 must assess overlap, workload, difficulty,
readability, edge cases and solution demands outcome-blind; it may reject this candidate or require a
new version. No equivalent reproduction/localization form set or extra held-out alternatives is
claimed here. The 14 installed forms still include two trace items in one baseline family and only
one family each for transfer and retention. The inventory makes these limitations inspectable.

## Reference and adversarial fixture matrix

Author tests use the existing `JavaCodeRunner`, trusted packaged harness and `JavaMechanismVerifier`.
They write no learner attempts/evidence. Installed production evaluation of every candidate remains
`UNSUPPORTED`, regardless of author-test success.

| Fixture | Author result required by test | Claim boundary |
| --- | --- | --- |
| Synchronized reference (three repetitions) | Structural boundary `PASSED`; sequential and finite concurrent cases pass | Supported monitor pattern + tested invariants, no universal safety claim |
| Missing/repeated-release mutant | Structural admission, deterministic assertion failure | Crediting absent claims violates conservation |
| Duplicate-claim mutant | Structural admission, deterministic assertion failure | Reusing an active ID violates rejection/no-mutation |
| Rejected-reservation mutation mutant | Structural admission, deterministic assertion failure | Failed operation must not debit capacity |
| Constant-free-count mutant | Structural admission, deterministic assertion failure | Output cannot substitute for state invariants |
| Unsynchronized lost-update mutant | Controlled boundary `FAILED` | No probabilistic stress rejection |
| Volatile-only compound-update mutant | Controlled boundary `FAILED` | Visibility is not compound atomicity |
| Split-monitor mutant | Boundary never `PASSED` | Unsupported mechanism cannot verify atomicity |
| Functionally valid unsupported reference | Real finite cases pass; structural `UNSUPPORTED` | Correct unfamiliar Java is not labeled learner failure |
| Uninstalled candidate in real evaluator | Overall `UNSUPPORTED`, no check `PASSED`, runner never consulted | Author validation does not authorize grading |

Existing evaluator regression fixtures separately exercise compilation/runtime/timeout/cancellation,
partial/inconclusive/unsupported/infrastructure outcomes and reviewed installed mechanisms. No policy,
assertion, skip or redaction rule is relaxed in this slice.

## Remaining gates and ownership

1. [#245](https://github.com/MrH3lmy/CodeFit/issues/245): complete independent forms, exact publication/adapters,
   baseline enumeration compatibility and outcome-blind human equivalence/rubric review.
2. [#246](https://github.com/MrH3lmy/CodeFit/issues/246): frozen local assignment, PRACTICE-only arms and
   cohort-wide feedback embargo across hints, saved recovery/results, journal, rewards and exports.
3. [#247](https://github.com/MrH3lmy/CodeFit/issues/247): equal-arm, restart-safe active-time accounting.
4. [#248](https://github.com/MrH3lmy/CodeFit/issues/248): reviewed opt-in consent, withdrawal/deletion and
   intentional allowlisted summary export. No automatic export/P2P publication.

The independent study owner must also approve the preregistration, freeze record, analysis fixtures,
sample/attrition/missing-data rules, versions, reward condition, dates and signoffs. Passing CI cannot
complete those gates. No participant enrollment, allocation, pilot, learning analysis or go/no-go
expansion decision occurs in #243.

## Verification and adversarial review

Final commands/counts and exact commit/CI evidence are recorded in the draft PR and
[gap-analysis completion record](pilot-readiness-gap-analysis.md#implementation-completion-record).
New tests are in [readiness](../../src/test/java/com/codefit/evolution/readiness/): metadata exactness,
role/family/check/invariant parity, digest integrity and private-content omission; actual reference/
mutant execution; fail-before-runner held-out practice; and whole-database row preservation across
repeated inspection/startup/rejected presentation, hint, solution, practice and submission routes.
The isolation test preserves a real existing authorized draft, exposure and session. No learner DB
is used. Real JavaFX regressions cover saved recovery, stale editors and authorized exposure routes.

Adversarial conclusions: no installed candidate route, evidence/reward credit or new export exists;
metadata cannot self-certify parity; existing authorized learning hints/results remain intentionally
available and block study execution until #246. Versioning, replay/backfill and signed P2P boundaries
are regression-tested rather than reimplemented. Linux/Xvfb does not validate native Windows/macOS,
assistive technology or human content equivalence. Existing #228/#229/#230/#238/#239 follow-ups stay
outside this change.
