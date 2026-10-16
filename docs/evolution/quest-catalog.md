# EV-05: offline quest and challenge catalog

Implementation of [#213](https://github.com/MrH3lmy/CodeFit/issues/213), under
[#208](https://github.com/MrH3lmy/CodeFit/issues/208). Based on main `85551f1` after PR #223.
This document describes implemented behavior. Evaluation, mastery estimates, selection policies,
rewards and the Evolution screen remain owned by #214, #212, #215, #217 and #216 respectively.
The catalog is usable through the application service and offline fixtures; it adds no JavaFX route.

## Contracts and ownership

| Contract | Implementation | Boundary |
| --- | --- | --- |
| `EvolutionQuest` | [quest records](../../src/main/java/com/codefit/evolution/quest/EvolutionQuest.java) | Exact ID/version, domain/graph, cohort, objective/narrative, observable targets, graph prerequisites, ordinal difficulty, active effort, candidates, declarative family/unassisted completion requirements and optional milestone ID. Separate from `DailyQuest`. |
| `ChallengeSpec` | [challenge records](../../src/main/java/com/codefit/evolution/quest/ChallengeSpec.java) | Exact targets and graph, stable family/variant/cohort, objective, assets, runner, hints, retry policy, author/license/source, exact rubric. Definition contains no learner result. |
| `ChallengeRubric` | [check contracts](../../src/main/java/com/codefit/evolution/quest/ChallengeRubric.java) | Identifiable required/optional checks, target behavior indices, specification, trusted verification approach, resource refs, exact I/O cases, timeout and limitations. No scoring implementation. |
| Publication | [QuestCatalogRepository](../../src/main/java/com/codefit/repository/QuestCatalogRepository.java) | One validated pack per transaction. Identical replay succeeds; conflicting exact versions fail. Stable IDs cannot move between families, variants, domains or cohorts in later versions. A family cannot move to a hold-out cohort. |
| Content | [bundled pack](../../src/main/resources/evolution/packs/java-debugging-concurrency/v1/) | Original prompts, starter code, harnesses, correct references, known mutants, traces and hints; MIT license included. No runtime network dependency or remote content import. |
| Presentation and aid | [ChallengePresentationService](../../src/main/java/com/codefit/service/ChallengePresentationService.java) | Exposure commits before prompt, hint or solution returns; uses existing sessions/drafts for Java. No attempt, evaluation, evidence, XP or mastery writes. |
| Historical exposure | [ChallengeExposureRepository](../../src/main/java/com/codefit/repository/ChallengeExposureRepository.java) | Immutable presentation receipts and append-only aid/skip/familiarity events. New variant IDs and content versions cannot erase family history. |

`CatalogRef` versions are positive and explicit. `tag()` produces `id/vN`, compatible with EV-03's
bounded `EvaluationReference` tags; the `@N` spelling is only a Problem natural key.
`BehaviorTarget` pins `SkillRef` and a zero-based index in its immutable observable-behavior list.
A future graph version does not reinterpret existing targets. Catalog publication checks the full
transitive prerequisite ancestry outside the target set against the exact graph. This validates the
curriculum metadata, not the learner's eligibility or proficiency.

Difficulty 1–4 in this pack denotes reproduction, compound atomic update, multi-state invariant and
lock-order/liveness work. It is an author ordinal, not calibrated ability. Effort is an estimate of
active minutes. Quest completion requirements are declarations for future evaluation and policy;
opening, finishing manually, or passing one sample never completes them here.

## Published content and validation

Each `challenge.properties` and `*.quest.properties` is a canonical sorted UTF-8 metadata manifest.
[CatalogCodec](../../src/main/java/com/codefit/evolution/quest/CatalogCodec.java) rejects duplicate,
unknown, oversized and noncanonical fields. Constructor validation rejects invalid identities,
missing primary assets, duplicate checks/cases, out-of-task target mappings, missing check assets,
invalid hint ladders, ungraded reflection as a required gate, and executable checks without exact
input/output cases. Publication resolves every candidate and exact graph/behavior/prerequisite,
validates cohort compatibility and enough distinct families for quest completion, and reads every
asset to verify its SHA-256.

The challenge manifest digest is SHA-256 of `CatalogCodec.encode(spec)` in UTF-8. It includes all
asset digests and the complete rubric. Rubric digest is SHA-256 of `CatalogCodec.encode(rubric)`.
Assets use SHA-256 of their exact bytes, including final newlines. A scoped `.gitattributes`
rule preserves LF checkout bytes; fixture output also uses explicit LF. There are no timestamp-dependent
`Properties.store` headers. Altering content requires a new manifest/version and new asset path;
retain all historical paths. SQL stores immutable metadata and hashes, not another Java statement,
solution or attempt bank. Missing/changed installed assets block presentation; they are never silently
substituted with latest content. Replaying publication after an application upgrade also checks
existing bindings and rejects conflicts.

Adding content means authoring versioned assets, updating their exact hashes, encoding a validated
manifest, and explicitly publishing it in an additive migration. The current pack index is fixed;
there is no editor, automatic variant generator, mutable active-version pointer or plugin framework.
Read-time asset integrity is checked even after the migration has been recorded. An old asset removed
from a later build fails closed, so release packaging must retain historical content.

## Training pack

All definitions pin `java-debugging-concurrency@1`, domain `software-engineering`, skill definition
version 1. Atomic/invariant challenges map directly to both appropriate behavior targets; prerequisite
membership alone is never counted as evidence. Each family belongs to exactly one cohort.

| Challenge IDs (all v1) | Family | Cohort | Engineering situation |
| --- | --- | --- | --- |
| `baseline-trace`, `baseline-localize` | `trace-diagnostic` | BASELINE | Predict the lost-update trace; separately localize an off-by-one defect from an assertion symptom. Keyed #104 diagnostics. |
| `baseline-counter` | `diagnostic-counter` | BASELINE | Repair first-publication and per-route request counts in a keyed map; distinct from scalar practice telemetry. |
| `counter-repair`, `counter-repair-b` | `metric-counter` | PRACTICE | Unit/batched telemetry increments; related variants, never independent transfer. |
| `atomic-boundary-drill` | `compound-update` | PRACTICE | Capacity-one admission, whole check/claim boundary and rejected empty leave. |
| `remaining-capacity` | `bounded-capacity` | PRACTICE | Reservation IDs, capacity conservation, cancellation and duplicate requests. |
| `distinct-id-accumulator` | `deduplicated-accumulator` | PRACTICE | First accepted payload per unique event; map/set/sum transition and duplicate rejection. |
| `regression-localize` | `pagination-boundary` | PRACTICE | Exclusive value cursor versus array position and inclusive boundary regression. |
| `lock-order-repair` | `two-resource-order` | PRACTICE | Opposite account transfers, lock cycle, conservation and stable lock order. |
| `inventory-transfer` | `inventory-reservation` | TRANSFER | Broker redelivery after cancel, non-reusable order IDs, stock held/free conservation. |
| `queue-retention` | `delayed-queue-accounting` | RETENTION | FIFO claim, in-flight accounting and duplicate acknowledgment. |
| `post-ledger` | `post-ledger-accounting` | PILOT_POST | Signed absolute contributions, replacement revisions and stale delivery. |
| `post-expiry` | `post-expiry-transition` | PILOT_POST | Logical-time inclusive expiry, release-once and no lease resurrection. |

Totals: **14 challenges, 12 families, 7 quests**; BASELINE 3, PRACTICE 7, TRANSFER 1,
RETENTION 1, PILOT_POST 2. Capacity accounting and unique-event accumulation require different
invariants and state transitions; they supply independent practice families for the planned atomic
update progression. Related counter variants are deliberately correlated. The initial diagnostic and
post cohorts are reserved author pools, not validated equivalent psychometric instruments. #218 must
freeze additional counterbalanced forms and its outcome protocol before enrollment. Exhaustion means
repeat/familiar content, not a new transfer claim.

The Broken Counter quest can be followed with reproduction, counter repair, atomic-boundary drill,
independent capacity and deduplication work, then reserved inventory transfer and a delayed queue
visit. This supplies the content for the approved [journey](java-debugging-concurrency.md); it does not
implement automatic ordering, prerequisite enforcement, delay eligibility, or adaptation.

### Checks and executable fixtures

Java assets consist of an editable package-private `Exercise` plus one public `Main` harness. The
starter is runnable through the existing `JavaCodeRunner`/workspace infrastructure; no Maven inside
the app or additional compiler is needed. A reference or mutant fragment is assembled with the pinned
harness for author tests. Changed inputs and rejection/state branches are included in each public
functional case. Counter fixtures include monitor and `AtomicLong` references, a deterministic dropped
update mutant and a volatile compound-update mutant. Lock fixtures include a conservation mutant and
a separate direction-dependent lock-cycle mutant.

Every executable concurrency rubric has **required** functional and operation-boundary checks.
The latter specifies the forbidden history/invariant and requires a trusted controlled schedule or
validated structural mechanism in #214. **An unavailable handler must yield unsupported/inconclusive,
never pass.** Sequential totals cannot establish atomicity; an operation-trace answer alone cannot
establish that the submitted code implements the repair. The `concurrent-observation` check uses a
start latch, joined workers, propagated worker failures, exact finite totals and a bounded deadline,
but is optional and explicitly observational. JVM schedules are not controlled by a seed. A timeout
cannot distinguish a deadlock from infrastructure/resource failure without #214's context.

`JavaTrainingPackAuthorTest` compiles and runs all 12 correct references against functional and
concurrency inputs, the extra atomic references where supplied, and rejects all 12 functional
mutants on deterministic cases. It checks the two diagnostic keys separately. The volatile and pure
lock-cycle mutants are provided for #214's reliable detection tests, **not** claimed deterministically
rejected by current stress fixtures. Full grading, partial scoring, durable run/check outcomes,
submission artifact binding and production rubric verification are not implemented or claimed.
The current local Java runner remains a bounded process runner, not a secure sandbox.

## Persistence and existing workflows

Migration **15** adds:

- `evolution_challenge_families`, `evolution_challenge_rubrics`,
  `evolution_challenge_definitions`, `evolution_quest_definitions`, `evolution_quest_candidates`;
- `evolution_assessment_reservations` for the #104 source-selection boundary;
- `evolution_challenge_presentations`, `evolution_challenge_exposure_events`.

[QuestCatalogSchema](../../src/main/java/com/codefit/config/QuestCatalogSchema.java) uses immutable
update/delete guards for catalog/receipt/event rows, composite candidate/presentation foreign keys,
unique version bindings and exactly one backing source per challenge. Twelve Java variants bind
Problems via platform `CODEFIT_EVOLUTION`, external code `challenge-id@version`, neutral title
`Evolution challenge`, neutral topic and no URL/resources. There are **no RoadmapEntry inserts**.
The two keyed diagnostics use existing assessment item/variant tables, exact/accepted-answer
validation configuration and catalog reservations. They are not Java Problems or a second quiz bank.
Ordinary #104 items and their rotation/validators are unchanged. A conflicting imported reserved
platform, workbook membership, mutable reserved header or diagnostic update is rejected.

Generic discovery filters Evolution Problems from library topics, distinct topics/platforms, search,
curriculum-new/revisit selectors and ordinary ProblemService reads. Active Evolution sessions are
not resumable through the ordinary dashboard. Navigation, generic workspace/manual finish,
guidance, Java draft/local-test reads and reflection-card creation reject their IDs even after
presentation. Trusted repositories remain available to coordinators; they are not rendering APIs.
Reserved assessment items are absent from ordinary repository reads and weekly selection. The
reservation API can also protect an existing item, retaining known historical familiarity.

New transaction connections enable foreign keys before beginning and acquire SQLite's writer before
history-dependent reads. The shared-Connection publication, assessment, session and draft operations
never commit on their caller's behalf. Any SQL/domain failure rolls back the complete command;
migration domain errors are converted to SQL errors for the established migrator. Migration 15 is
additive: old attempts, progress, drafts, assessments, workbook order, XP and peer records are not
rewritten. Downgrade is unsupported. Tests that reconstruct older schemas remove only later additive
catalog structures; original preservation assertions remain. Workbook tests still assert all 923
ordinary Problems / 926 memberships and separately assert the new catalog bindings are preserved.
Recheck migration 15 against main before merging if concurrent P2P work advances the head.

## Presentation lifecycle and #216 integration

```java
var service = new ChallengePresentationService();
// Persist/reuse this ID for one deliberate presentation across retries and lost responses.
var request = new ChallengePresentation.Request("presentation-01",
        new CatalogRef("broken-counter", 1), new CatalogRef("counter-repair", 1),
        QuestTypes.Cohort.PRACTICE, QuestTypes.Familiarity.UNKNOWN);
var view = service.present(request); // exposure + existing session/draft committed first
// Only now may #216 render view.prompt(), view.draft() and view.publicChecks().
service.revealHint("presentation-01", "hint-command-01", 1); // commit before hint returns
service.skip("presentation-01", "skip-command-01");        // no failure/attempt/evidence
var resumed = service.resume("presentation-01");           // same receipt after restart
```

A new deliberate return can use a new ID; a retry of the same command must reuse its ID. Reusing an ID
with different quest, challenge, cohort or familiarity declaration is an error. Skip does not erase
exposure. Hints, solution reveal and later familiarity declarations have their own stable command IDs,
with conflicting reuse rejected. Aid levels/solution/familiarity only accumulate across the family,
including earlier versions and related variants. Existing drafts and known historical attempts/hints
also prevent a fresh familiarity claim; unavailable historical assistance is not invented as an exact
hint level. Returning to a variant resumes its existing draft, never overwrites learner edits.

Each immutable receipt pins quest/challenge/family/variant/cohort, UTC instant, declared familiarity
and **prior** family/variant counts, classification and known aid/familiarity. `FIRST_PRESENTATION`
means first registered locally, **not verified unseen**. `UNKNOWN` familiarity stays unknown. A new
variant of a presented family is `RELATED_VARIANT`; the same variant across versions is `REPEAT`.
`currentContext` includes the current receipt and all aid known now, while `receipt.prior()` preserves
the original pre-presentation context. Use the latter for initial classification, not current counts.
History retains timestamps and IDs; #215 must verify actual elapsed time, intervening practice and
clock anomalies before calling a reserved queue task delayed retention.

On a crash before commit nothing returns and the transaction rolls back. On a crash/lost response
after commit exposure conservatively remains even if the user did not see a frame. Retrying the same
request recovers its receipt; resume never silently creates a new one. Assets that fail integrity
checks block presentation/reveal. Any alternative future renderer must call this boundary; do not
navigate to today's generic workspace by Problem ID to bypass it. No UI was added in this ticket,
which is the explicit fail-closed option in #213. These are privacy/source-routing boundaries, not
exam secrecy or protection against someone reading their own bundled files or editing SQLite.

## #214 handoff and evidence/privacy guarantees

Call `contextForSubmission(connection, presentationId)` in the **same writer transaction** that
prepares the deliberate submission. Freeze its receipt, exact challenge/rubric/manifest/asset
identities, current aid/familiarity, backing source and exact submitted artifact/harness digest.
Execute outside a database transaction with the existing runner. Finalize one authoritative evaluation
and one existing source attempt with all applicable check outcomes; do not emit independently counted
success observations per check. EV-03's `EvaluationReference` can use rubric `ref.tag()`, family ID,
check IDs and artifact SHA-256. Its `ChallengeLookup` must compare durable evaluation metadata on the
same connection; catalog metadata or a presentation receipt cannot authorize `LOCAL_CHECK` evidence.
A keyed diagnostic must not turn the old assessment boolean into machine-verified code evidence.

#214 must retain the approved non-judge completion marker, artifact snapshot, idempotent submission,
crash recovery, rubric handler/mutant checks and exclusions from existing opted-in P2P aggregates.
This catalog writes no attempts or evidence and invokes no evidence backfill. No new peer payload,
consent, grant, publication, sync, progress metric, XP or mastery path is introduced. Code, prompts,
answers, drafts, exposure history and learner choices remain local. Existing peer transport and
sharing contracts are unchanged. #212/#215 consume exposure honestly; cohort labels alone never mint
held-out transfer, retention, unassisted success or independent evidence.

## Acceptance and verification

| #213 criterion | Implemented proof |
| --- | --- |
| Stable versioned definitions and checks | Immutable records, exact graph/skill/behavior refs, canonical manifests/digests, publication conflict tests and SQL guards. |
| Rubric pass/fail beyond XP | Exact functional cases, required controlled-operation specification, explicit unsupported handling for #214; no XP coupling. |
| Repeats/variants distinguished | Immutable receipts, family/variant history across versions, aids/familiarity and concurrent command replay tests. |
| Permission-safe original content | Fourteen original incident tasks with author/source metadata and bundled MIT license. |
| Content validation | Every asset hash, exact targets/prerequisites, candidates, mappings, checks/cases, primary roles and cohort boundaries validated; malformed/corrupt cases tested. |
| Sample good/bad solutions | Dynamic author tests run monitor/atomic references, exercise finite concurrent cases, reject deterministic functional mutants and retain unsafe concurrency mutants for #214. |
| Compatibility and offline privacy | Isolated migration/restart/rollback/source-row snapshots; import, curriculum, assessment, navigation, session/draft and no-P2P-write tests; full Java 21 suite. |

See [quest-catalog-verification](quest-catalog-verification.md) for exact commands/results and the
adversarial review. No learning-gain claim or pilot result is implied by a catalog or green tests.
