# Provenance-aware skill evidence (EV-03)

Implementation for [#211](https://github.com/MrH3lmy/CodeFit/issues/211), under
[epic #208](https://github.com/MrH3lmy/CodeFit/issues/208), built on main
`c110ea6a0045d83b37b151f93f5a45de588eed63` (merged #220). This document describes implemented
behavior as of EV-03. [EV-06](challenge-evaluation.md) now supplies durable challenge evaluation and
same-connection source validation. Mastery estimation, rewards and Evolution UI remain future work.

An observation records what a source actually says, including its uncertainty. `PASS`, a category
mapping, and `effective()` are never claims of independent ability or proficiency.

## Code and ownership

| Boundary | Implementation | Responsibility |
| --- | --- | --- |
| Immutable metadata contracts, canonical identity | [evidence package](../../src/main/java/com/codefit/evolution/evidence/) | Contract version 1; separate outcome, provenance, assistance, exposure, time and availability; submission/check references; correction lineage |
| Persisted-source adapters | [LearningEvidenceSourceRepository](../../src/main/java/com/codefit/repository/LearningEvidenceSourceRepository.java), [LegacyEvidenceClassifier](../../src/main/java/com/codefit/evolution/evidence/LegacyEvidenceClassifier.java) | Explicit metadata-only SQL and deterministic interpretation of raw persisted fields |
| Mapping publication | [EvidenceMappingRepository](../../src/main/java/com/codefit/repository/EvidenceMappingRepository.java) | Exact immutable graph/skill references and contextual rules; no automatic classification |
| Append, lineage, source validation, retrieval | [EvidenceRepository](../../src/main/java/com/codefit/repository/EvidenceRepository.java) | One canonical observation per event/revision, immutable interpretations, fail-closed evaluation boundary, exclusion reasons |
| Backfill and explicit source correction | [EvidenceIngestionService](../../src/main/java/com/codefit/service/EvidenceIngestionService.java) | Bounded per-source/per-mapping durable cursors, atomic batches, explicit old-row refresh |
| Desktop lifecycle | [EvidenceBackfillWorker](../../src/main/java/com/codefit/service/EvidenceBackfillWorker.java), [CodeFitApplication](../../src/main/java/com/codefit/CodeFitApplication.java) | Delayed finite background ticks; bounded shutdown; no worker during DB initialization |
| Schema | [EvidenceSchema](../../src/main/java/com/codefit/config/EvidenceSchema.java), [SchemaMigrator](../../src/main/java/com/codefit/config/SchemaMigrator.java) | Additive migration 14; no historical scan or source rewrite |

No source service, scheduler, XP calculation, legacy card mastery/readiness calculation, wire type,
consent rule or sharing projection is replaced. The engine reads source records independently after
they commit; source actions do not fail because evidence processing is temporarily unavailable.

## What the source audit established

| Persisted source | Classification in this implementation | Unrecoverable or qualified facts |
| --- | --- | --- |
| `problem_attempts`: ID, problem ID, attempt number, verdict, completion origin, submitted timestamp | AC/ACX = reported PASS; WA/TLE/RTE/CS/MLE = reported FAIL. Always SELF_REPORTED; unsupported values = UNAVAILABLE. Attempt >1 or ACX is REPEAT; other exposure UNKNOWN. Correlation `problem:<id>`. | An external judge code was entered by the learner, not authenticated. Per-attempt hint/reveal information does not exist; assistance UNKNOWN, regardless of current `ProblemProgress.solvedWith`. Topic is current contextual metadata. |
| `review_history`: raw validation code, rating, hint flags, boss flag, timestamp; parent card category | EXACT/CLOSE_SPACING/JAVA_CORRECT = OBJECTIVE_REVIEW PASS. DIFFERENT/JAVA_WRONG_OUTPUT/JAVA_UNEXPECTED_EXCEPTION/JAVA_MISSING_EXPECTED_EXCEPTION = OBJECTIVE_REVIEW FAIL. SUBJECTIVE = SELF_REPORTED rating outcome. Missing/unrecognized validation = LEGACY_UNSPECIFIED rating outcome, explicitly tagged fallback. | Never calls `ReviewHistory.isObjectivelyCorrect()`. A validator match is scoped recall, not unseen problem solving. SQL/regex results are persisted as the existing generic EXACT/DIFFERENT tags. No immutable answer/card/rubric artifact snapshot. Current category can have changed since review. |
| Review hint flags | `hint_usage_recorded=0` means UNKNOWN; otherwise HINT or NO_IN_APP_HINT_RECORDED. All reviews, including boss rounds, are FAMILIAR_RECALL. | No recorded in-app hint is not proof of no external help. No fabricated hint level. |
| Review inconclusive tags | TIMED_OUT, TIMED_OUT_WITH_ATTEMPT, EMPTY, JAVA_PENDING, JAVA_COMPILE_ERROR, JAVA_TIMEOUT, JAVA_REJECTED_UNSAFE_SNIPPET, JAVA_SANDBOX_UNAVAILABLE = LEGACY_UNSPECIFIED / UNAVAILABLE | Compile errors can also mean malformed card configuration; timeouts/rejected execution are not automatically capability failures. No positive rating can override these tags. |
| `assessment_attempts`: ID, item, snapshotted category, correct boolean, timestamp | PASS/FAIL as recorded, always LEGACY_UNSPECIFIED, assistance and exposure UNKNOWN; correlation `assessment:<item-id>` | No grading-kind, hint, reveal or exposure snapshot, even for newly written rows today. Current item type cannot retroactively establish grading provenance. Future prospective capture belongs to #212/#214 as appropriate. |
| Existing workspace/runner | No adapter manufactures historical evaluation evidence | Executions are transient, not a versioned durable challenge evaluation history. The Java runner is not assumed to be a secure sandbox. Persisted Java review tags have only the review scope above. |

For subjective/legacy ratings GOOD/EASY produce a reported PASS, AGAIN a reported FAIL;
HARD and unsupported ratings produce UNAVAILABLE because difficulty does not establish correctness. These retain their provenance; no new objective truth is
assigned. Outcome and provenance must always be read together.

The original three legacy INSERT paths omit the timestamp column and SQLite supplies
`CURRENT_TIMESTAMP`. The timestamp on a returned in-memory Java object may differ from the stored
row. Strict `yyyy-MM-dd HH:mm:ss` persisted values are interpreted as UTC with
`LEGACY_SQLITE_UTC` and a limitation, not as an authenticated event clock. Other formats, including
ambiguous offset-free Java timestamps from other writers, retain their original bounded value but
have no normalized observation instant. Imported/prior-solved/unknown problem completion origins
have no inferred completion instant. `recordedAt` is separate ingestion time; it never fills a
missing observation time. This corrects the foundation audit's assessment timestamp statement. EV-06's reserved local ProblemAttempt
writer is a separate scoped path that stores the frozen submission UTC time explicitly.

## Identity, correlation and immutable history

`EvidenceIds.id(SourceRef)` hashes a length-delimited versioned namespace, source type, positive
source row ID, canonical event kind and positive revision using SHA-256. Event kind is fixed by source
type; adapters cannot create a separate event for each check. `EvidenceIds.root()` omits revision.
Neither ingestion time, mapping version, outcome nor check ID affects source identity.

An ORIGINAL starts at revision 1. A correction must name the current head and exactly the next
revision. Corrections cannot branch, skip revisions or point to another source's head. Identical
replay returns DUPLICATE and retains the first recorded time. Reusing the same event identity with a
different payload rejects the transaction. Replaying an old revision never moves the head backward.
A RETRACTION appends a new revision with UNAVAILABLE outcome and RETRACTED availability, a reason,
actor and previous evidence ID; it never deletes history or restores a previous success.

The source root, evidence ID/revision, previous ID, correlation key and evaluation submission/family
reference let #212 group related evidence. Repeated attempts on a problem remain distinct
observations, but share a problem correlation and are never automatically independent solves.
This engine deliberately does not count successes or estimate proficiency.

For a challenge, an observation summarizes **one deliberate submission**, with 1–64 distinct check
IDs. Unique submission and linked-problem-attempt constraints prevent two source aliases from
claiming that submission. The linked manual problem observation receives
EVALUATION_AUTHORITATIVE exclusion, even if the evaluation later becomes unavailable or retracted:
manual AC must not reappear as replacement proof. A corrected evaluation can change its grading
metadata/check manifest through an explicit revision, but preserves submission, family, artifact
digest and linked attempt identity. A new deliberate submission uses a new source identity.

## Exact contextual mappings

A published `EvidenceMapping` pins one `GraphRef` and exact `SkillRef`s. Publication verifies the
graph is published and each target is a member of that exact graph. Version 1 starts a named mapping
lineage; vN explicitly supersedes vN-1. Reusing a version with different content rejects; identical
publication is harmless. No lookup silently selects a latest graph/skill version.

Migration 14 seeds only `java-practice-context@1`, pinned to `java-debugging-concurrency@1`:

| Exact source selector | Target definition |
| --- | --- |
| REVIEW / `JCIP Ch15 - Atomics and Nonblocking` | `java.concurrency.atomic-update@1` |
| REVIEW / `JCIP Ch10 - Liveness Hazards` | `java.concurrency.liveness@1` |
| ASSESSMENT_ATTEMPT / `Concurrency` | `java.concurrency.atomic-update@1` |

These are deliberately weak contextual links. The seeded Concurrency assessment scenarios concern
lost updates and check-then-act; their boolean result still lacks grading provenance. JCIP recall
is not proof of performing the graph's engineering behavior. Broad arbitrary problem topics have no
seeded mapping. There is no fuzzy matching, AI mapping, case folding or inference of prerequisites.
Unmapped observations still persist with an empty target set. Category values longer than 128
characters use an empty unmapped selector rather than truncating into an accidental match.

An immutable interpretation stores source context once per observation/mapping. Exact targets are
resolved through the immutable rule set, rather than duplicating attempts or producing new events.
Changing mapping versions therefore leaves evidence IDs and recorded times unchanged. A new mapping
can remove a target entirely. Old mapping views are marked MAPPING_SUPERSEDED immediately, including
while the new version's bounded backfill is incomplete; availability temporarily decreases rather
than falling back to stale positive evidence. Source category changes require a new source revision,
not a silent rewrite of an interpretation. Retractions preserve their prior context even after a rename.

## SQLite and transactions

Migration 14 adds seven local tables:

- `evolution_evidence_sources`: canonical source identity and current head pointer.
- `evolution_evidence_observations`: ordered immutable revisions, ingestion time, previous pointer,
  and bounded version-1 binary metadata (no Java object deserialization).
- `evolution_evidence_mappings` and `evolution_evidence_mapping_rules`: sealed graph-pinned rules.
- `evolution_evidence_interpretations`: immutable observation/mapping/context pairs, including unmapped ones.
- `evolution_evidence_cursors`: durable numeric source-ID cursor for each exact mapping and source type.
- `evolution_evidence_evaluation_links`: unique deliberate submission and optional manual-attempt aliases.

Foreign keys protect exact references; triggers reject history update/deletion and rule insertion
into sealed mappings. Repository operations enforce lineage, source validation, publication versions
and interpretation consistency. Direct SQL by a database owner is not a security boundary.

`EvidenceRepository.transaction()` opens the existing local connection, enables foreign keys before
starting a transaction, sets a bounded SQLite busy timeout, and acquires the writer lock before
reads. All SQL/runtime failures roll back; commit failures also roll back. Closing a failed connection
does not reset auto-commit and accidentally commit a partial batch. Caller-owned `append(Connection,...)`
never commits: callers must enable foreign keys before the transaction, coordinate their write lock,
and roll back the **entire transaction on any exception**. This follows existing transaction conventions
without changing the shared `Transactions` helper or catalog implementation.

Only evidence metadata/context is copied: no submitted answer, source code, notes, prompt, hint text,
reference answer, timings or complete attempt model. Source pointers can become missing, and history
remains useful as a qualified audit trail. Schema migration performs no history scan and changes no
source rows. Downgrading a binary leaves additive tables in place; never drop them on a real learner DB.
Recheck the migration head when rebasing before merge (main was still at 13 when verified).

## Bounded ingestion and correction

`ingestBatch(mapping, sourceType, limit)` accepts 1–256 records and uses
`id > last_source_id ORDER BY id LIMIT ?`, independent of dashboard limits. Source reads,
observations, interpretations and cursor advancement share one transaction. Failure at any point
rolls back the batch and cursor; replay after restart retries the same IDs. A new mapping gets a
separate cursor and reinterprets current heads, including retractions, without creating source events.
The desktop worker remains explicitly pinned to the initial mapping; publishing a new mapping does
not automatically select a graph or schedule it. Its owner must invoke ingestion for that exact
mapping and choose it for consumers. Active graph/selection policy remains #222.

`CodeFitApplication` starts one daemon after DB initialization. Its first tick is delayed five seconds;
subsequent ticks have a 30-second fixed delay. Each tick processes at most 64 records per legacy source
(192 total), then yields. Live writes become visible through the same incremental path; large histories
can require multiple ticks/sessions. Exceptions defer that source batch without interrupting study;
logs identify only the source kind, never private records. Normal shutdown interrupts the worker and
waits at most six seconds. DB initialization and migrations never start workers.

Old source rows are append-oriented in normal app flows. Numeric cursors intentionally do not scan
old IDs on every startup to detect external edits. Retrieval detects missing/changed source facts
conservatively. `refresh(type, id, mapping, reason, actor)` explicitly appends a correction from the
current persisted source, or returns DUPLICATE if facts/context already match. Retraction uses
`head(...).observation().retract(reason,actor)` plus `append(...)` with the stored `context(...)`.
Restoring a retracted source requires an explicit correction; replay/backfill cannot restore it.
A changed source encountered during remapping rejects that finite batch until explicitly refreshed;
it is not silently relabeled or skipped. Other source kinds can still advance. No repair UI is added here.

## Retrieval for #212

```java
var repository = new EvidenceRepository();
var page = repository.forSkill(
    new SkillRef("java.concurrency.atomic-update", 1),
    EvidenceMappingRepository.INITIAL_MAPPING,
    0, 64); // exclusive sequence cursor; use last returned sequence for next page
```

`forSkill` requires an explicit exact mapping and skill, returns immutable ordered `EvidenceView`s,
and caps a page at 256. Each convenience call uses one SQLite snapshot. For a coherent multi-page
input set, open a short caller-owned read transaction and use
`forSkill(connection, skill, mapping, cursor, limit)` for every page on that connection, then commit
or roll back it. This overload never commits or closes the connection. Do not combine independently
read active pages across a concurrent correction/retraction as if they were one snapshot; #212 owns
estimate input revision/commit coordination. Release the read transaction before expensive policy work. Each view includes stored metadata, source
root derivable from `EvidenceIds.root(observation.source())`, mapping, exact skill, current availability
and exclusion reasons: SUPERSEDED, RETRACTED, MAPPING_SUPERSEDED, SOURCE_UNAVAILABLE, SOURCE_CHANGED,
EVALUATION_AUTHORITATIVE. Missing parent records also make a source unavailable. Changed facts/context
are excluded pending explicit correction, without inventing a failure. A raw source's existence alone
cannot restore a withdrawn revision. Unsupported contract payloads fail closed, not as usable successes.

`effective()` means a current available interpretation with no exclusion; it includes self-reported
and uncertain observations. `objectivelyEvaluated()` further requires non-UNAVAILABLE outcome and
OBJECTIVE_REVIEW or LOCAL_CHECK provenance. It does **not** require PASS, absence of hints or unseen
exposure. #212 must inspect those dimensions, group root/submission/problem/family correlations, and
choose one explicit mapping lineage for an estimate. Reading multiple mapping lineages or skill targets
must not turn the same root/submission into independent observations. Pagination includes excluded
history; advance the cursor even when a page contains no effective observations.

Source validation is bounded per returned page and stays inside this repository: #212 need not
reinterpret raw legacy rows. It may use uncertain observations as context but must not promote them
to objective capability evidence. Ingestion time is not a recency substitute for unknown event time.

## Durable evaluator integration for #214

There is no production challenge producer yet. The default `ChallengeLookup` always returns false;
LOCAL_CHECK ingestion is rejected and any such stored records are unavailable when read without a
matching lookup. Do not connect the old runner or a caller's boolean result to this boundary.

#214 will supply `new EvidenceRepository((connection, observation) -> ...)`, validating **all factual
metadata** against its durable evaluation on the provided connection: source ID, submission, family,
exact rubric/evaluator versions, artifact SHA-256, check IDs, outcome, time, assistance, exposure and
limitations. The check must be deterministic, bounded and local, including on retrieval. It must
reject missing, changed, incompatible or incomplete evaluation records. A callback returning true
unconditionally defeats the contract; this callback is trusted application code, not a sandbox.

Finalize the durable submission/evaluation, linked legacy attempt and evidence with
`repository.transaction(connection -> { ...; repository.append(connection, observation, mapping,
familyId); return ...; })`, or the documented caller-owned equivalent. #214 owns the evaluation rows,
artifact content, safety, grading and prospective source markers; this ticket only stores metadata
references and de-duplicates aliases. No external authenticated adapter is implemented:
AUTHENTICATED_EXTERNAL is reserved and rejected.

## Verification and limits

Verified on Java 21 with the existing Maven configuration:

- Focused source/model/repository/migration/lifecycle/privacy selection: **60 tests**, zero failures,
  errors or skips.
- Full `mvn -B test`: **1,350 tests**, zero failures, errors or skips, including the existing
  Java runner, curriculum, persistence, P2P transport and two-process integration tests.
- `git diff --check` and relative documentation links pass. The repository-root `codefit.db`
  remains absent, matching its initial state; test isolation guards also pass.
- Re-fetched remote main before publication: still `c110ea6a0045d83b37b151f93f5a45de588eed63`,
  migration head 13. Migration 14 has no current main conflict. PR CI runs separately.

Focused coverage includes:

| Requirement | Evidence |
| --- | --- |
| Provenance, unknown grading/hints/time, manual AC, objective/subjective review | `LegacyEvidenceClassifierTest`; adapter integration in `EvidenceRepositoryTest` |
| Stable identities, conflicting duplicates, revisions/retraction, stale replay, exact mappings and removals | `EvidenceRepositoryTest` |
| 301 attempts beyond dashboard limits, bounded pages, restart, unmapped records, transactional mid-batch failure, concurrent replay | `EvidenceRepositoryTest` |
| No invented LOCAL_CHECK; submission/check/attempt alias de-duplication; atomic finalization rollback | `ChallengeEvidenceBoundaryTest` (test-only durable evaluator) |
| Populated version-13 upgrade, partial-DDL rollback, retry, repeated initialization and unchanged source/catalog rows | `EvidenceMigrationTest`; existing `SkillCatalogMigrationTest` retains v12 upgrade assertions |
| Finite desktop ticks, delayed schedule and shutdown | `EvidenceBackfillWorkerTest` with controlled scheduler |
| No source content copied and no evidence IDs/mappings/skills in signed P2P bytes | extended `SnapshotLeakageTest`; unchanged protocol/privacy suite |

Tests use isolated SQLite files with `IsolatedDatabaseExtension` and the shared database resource lock;
the repository-root learner database must remain untouched. No desktop manual session, physical power
loss test, authenticated judge integration, new evaluator or real learning-gain study is claimed.

Risks retained explicitly: historical metadata cannot prove original category content or outside
independence; SQLite timestamps have a documented legacy assumption; externally edited old rows
need explicit repair; deleted sources reduce usable coverage; mappings are broad contextual links;
backfill is eventually consistent and requires runtime to catch up; immutable metadata accumulates
and no privacy-deletion workflow is introduced. Evidence remains in the existing local SQLite file,
with its existing filesystem protection, and has no peer/public export.

Next: **#213**, the versioned Java Debugging & Concurrency quest/content pack. It supplies the concrete
families, variants and rubrics needed by #214's durable producer. #212's pure estimate policy can now
consume these contracts, but a complete executable baseline still depends on #213/#214. #221 catalog
bulk optimization and #222 active graph selection remain separate follow-ups; neither is implemented.
