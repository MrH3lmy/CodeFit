# SQL & Transactional Data Consistency — author candidate v1

**AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW · 0 independent approvals · not executable.**

GraphRef `sql-transactional-consistency@1`; all six exact SkillRefs are version 1. The graph is in `graph.json`, outside application resources. `ACTIVE` is the existing definition lifecycle vocabulary: it means the proposed definition is not retired, **not** content approval, publication or learner capability. No production code loads this directory.

## Scope and authoring boundary audit

`SkillGraph` validates an immutable exact-version DAG; `SkillDefinition` requires nonblank, distinct observable behaviors. `SkillCatalogRepository` seals published definitions and graph membership and rejects conflicting version reuse. Publication is an explicit repository operation, separate from authoring. This packet invokes publication only inside isolated rollback/test databases to verify existing contracts.

`GraphSelectionRepository` persists an exact per-track pointer with CAS and immutable receipts. Publication never selects a graph. Selection in the disposable desktop track requires exact catalog membership and lifecycle eligibility; selection is not assessment authorization. `PracticeGraphService`, `PracticeSkillsService`, `MasteryEstimateService.capturePilot`, `AdaptationInputReader` and `JavaTrainingPack` deliberately support the existing Java pilot. Neither this packet nor a SQL-looking ID can install an adapter.

`EvidenceMappingRepository` validates exact mapping graph membership; a mapping is not a grader or evidence observation. Existing ordinal mastery requires genuine effective evidence and validated behavior coverage; no SQL inputs currently satisfy a supported evaluator. `mapping-proposal.json` is an **unpublished design proposal**, not an `EvidenceMapping` registration, selector or active mapping ref. No challenge/rubric/check/evaluator identities are fabricated.

#253 concerns independent review of the separate Effective Java corpus; it supplies a human-review governance precedent, not SQL approval. #269 describes a blocked, review-gated assessment adapter. Existing `KnowledgePreview` public-safe author drafts explicitly carry zero approvals and disabled runtime flags; it is neither a general graph publication service nor an approval ledger. This packet does not extend that preview or put SQL nodes into The Ascent.

## Target environment and entry requirements

Review scenarios target **PostgreSQL 17**, a major version to pin more precisely before implementation. They are not portable assertions about every SQL engine or CodeFit's SQLite persistence database. Basic SELECT/INSERT/UPDATE/DELETE, joins, predicates, NULL and elementary set/bag semantics are assumed entry knowledge, not separately approved graph nodes. Reviewers must decide whether this entry condition needs an independently curated prerequisite track before learner release.

All workloads, schema examples and labels here are original author-draft material. No live database, learner profile, external service or SQL runner is opened by this packet. Future execution needs a separately audited disposable PostgreSQL environment, exact minor image/build pin, roles, resource/cancellation controls and trusted harness isolation. The local Java runner is not a SQL evaluator or security sandbox.

## Six skills and technical boundaries

Exact definitions and three separately observable behaviors per skill are in `graph.json`. Behavior references B1–B3 below mean positions 1–3 in that immutable version; they are not independently counted solves.

| Exact SkillRef @1 | Boundary | Typical original scenario / misconception |
|---|---|---|
| `sql.relational-integrity` | Schema-enforced validity, keys, functional dependencies, NULL behavior and cross-row limits. Does not claim every invariant is declarative. | Reservation rows reference valid stock items, while total reserved quantity is a separate cross-row invariant. A CHECK does not generally turn a query over other rows into a safe aggregate constraint. Normalization does not eliminate all concurrency anomalies. |
| `sql.transactional-atomicity` | Commit/rollback scope and uncertain outcomes, not isolation between otherwise atomic transactions. | A transfer debits one account then fails before credit. Two autocommit statements are not one atomic transfer. A database commit does not atomically send an email. A disconnected client cannot infer rollback from a missing reply. |
| `sql.isolation-concurrency` | Reproduce anomalies under named PostgreSQL semantics and preserve a stated invariant. Does not prove all possible schedules. | Two on-call assignments each observe another available worker and independently remove themselves. PostgreSQL Repeatable Read prevents predicate-result phantoms within its snapshot but can admit write skew; it is not Serializable. |
| `sql.locking-conflicts` | Version checks, lock protocols, errors and bounded retries. Focuses on an implemented resolution after anomaly diagnosis. | Two stock transfers lock inventory rows in opposite order. Dropping a transfer to avoid waiting is not a valid liveness fix. FOR UPDATE locks existing tuples, not every absent tuple satisfying a conceptual predicate. |
| `sql.query-plans-indexing` | Workload-specific plan/result evidence and trade-offs, not memorized index syntax or universal latency. | An index helps rare-tenant lookup but may lose on a tenant containing most rows. Estimated cost is not wall-clock time; a sequential scan is not intrinsically wrong. EXPLAIN ANALYZE executes statements, so future write experiments need isolated rollback-safe fixtures. |
| `sql.idempotent-workflows` | Request identity and scoped durable effects across retry/crash; integrates boundaries and conflict handling. | Duplicate invoice requests and an outbox dispatcher fail after delivery but before acknowledgement. A unique key alone does not bind a request fingerprint, response and business update atomically. Outbox transport is at least once; global exactly-once delivery is not claimed. |

These are review targets, not claims that a learner has demonstrated any behavior.

## Prerequisite rationale

Every edge pins both endpoints at definition version 1; there are no external dangling refs.

| Edge | Why this is a preparation dependency |
|---|---|
| Relational integrity → atomicity | A transaction boundary must protect an explicit invariant, including its schema-enforced portion. |
| Relational integrity → query plans | Rewrites/indexes must preserve keys, multiplicity and NULL semantics before performance evidence is meaningful. |
| Atomicity → isolation | Distinguish an incomplete operation from interference between complete transactions. |
| Isolation → locking/conflicts | Choose a resolution against an identified history/invariant, rather than applying locks indiscriminately. |
| Atomicity → idempotent workflows | The key, fingerprint, business writes and result need a single durable atomic scope. |
| Locking/conflicts → idempotent workflows | Concurrent duplicate requests and retryable transaction aborts need explicit conflict resolution. |

No artificial edge from indexing to locking is required: indexing affects lock footprint/performance but is not a prerequisite for every correct conflict protocol. Query plans is a legitimate parallel leaf. The graph is a recommended learning dependency DAG, not automatic unlock rules. Independent reviewers must approve these edges and scope.

## Evidence and assistance principles

One deliberate submission is one correlated opportunity even if it exercises many checks. Changed identifiers, dataset seeds or graph versions do not create independent families. A passing familiar schema repair cannot count as unseen transfer. Hints and reference-solution reveals must be durably recorded before presentation and frozen with submission provenance by a future approved adapter.

An assisted result can support practice progress within verified scope, not independent success. UNKNOWN assistance/exposure remains uncertain. Explanations, annotations and self-reported results are UNVERIFIED without an actual authorized review mechanism. No rubric, PostgreSQL execution harness, narrative-review adapter or SQL-compatible estimation policy is registered here. Timeout/tool failure is not learner failure. Required unsupported mechanisms must block verified completion, not silently become optional.

Transfer must change business context, invariant and reasoning demands (e.g. stock reservation to capacity-limited subscription allocation), not merely table names. Retention needs a separately held-out family and the existing policy's delay/rehearsal conditions; the current V1 policy requires at least seven days without intervening same-behavior rehearsal. None of these proposed opportunities exists as an executable challenge yet.

See `assessment-matrix.md` for behavior-level scope and limitations, `review.md` for independent review, `release-policy.md` for versioning/release, and `provenance.md` for licensing status. Technical and pedagogical decision records are separately PENDING. Passing structural tests and matching digests establish package integrity only.
