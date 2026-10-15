# EV-02: implemented versioned skill catalog

Implementation for [#210](https://github.com/MrH3lmy/CodeFit/issues/210), following the accepted
foundation in merged [PR #219](https://github.com/MrH3lmy/CodeFit/pull/219). The catalog describes
capabilities and prerequisites only. It contains no learner proficiency, evidence, XP, quest selection,
UI, category inference or network integration.

## Fit with the existing application

Existing `Flashcard.skillCategory`, problem topics, training modules and `MasteryService` remain
unchanged. Their categories and progress describe existing learning flows; they are not a versioned
capability taxonomy. The catalog is additive and does not replace or seed those tables. Category-to-skill
mappings must be explicit, separately versioned work in #211, never inferred from string similarity.
The imported workbook remains authoritative for curriculum order. A prerequisite edge is not an
unlock, completion, skill score or assertion about a learner.

The implementation follows `DatabaseConfig`/`SchemaMigrator`, JDBC repositories and
`IsolatedDatabaseExtension`. Its pure model has no JavaFX, persistence, network or future-engine imports.
One repository handles this small catalog; there is no generic graph framework or new service hierarchy.

## Public contracts and queries

Pure immutable records live in [com.codefit.evolution](../../src/main/java/com/codefit/evolution/SkillGraph.java):

| Type | Contract |
| --- | --- |
| `SkillRef(skillId, definitionVersion)` | Exact historical skill identity. Positive integer version; lowercase stable ID with dot/hyphen-separated alphanumeric segments. Natural order is ID then version. No implicit latest-version resolution. |
| `GraphRef(graphId, graphVersion)` | Independent graph identity/version, with the same ID/version rules. Graph versions need not equal definition versions. |
| `SkillDefinition(ref, domainId, name, description, observableBehaviors, lifecycle)` | Nonblank descriptive text and a nonempty, ordered, duplicate-free behavior list; defensive immutable copy. Behaviors may contain multiline text and round-trip without normalization. Domain classification belongs to that exact version; reclassification requires a new version and leaves historical refs unchanged. |
| `SkillLifecycle` | ACTIVE or RETIRED, part of the immutable definition version. Retirement is published as a new version; old versions remain readable with their original lifecycle. |
| `SkillPrerequisite(prerequisite, dependent)` | Directed prerequisite → dependent, with exact refs. Self-dependencies, including two versions of the same stable ID, are invalid. The containing graph supplies graph identity/version. |
| `SkillGraph(ref, definitions, prerequisites)` | Nonempty validated snapshot. At most one version per stable skill ID. Exact endpoints must be members. Canonical immutable lists, no duplicate edges or cycles, including disconnected cycles. |

[SkillCatalogRepository](../../src/main/java/com/codefit/repository/SkillCatalogRepository.java) exposes:

- `findSkill(SkillRef)` and `findGraph(GraphRef)`: `Optional.empty()` when the exact published version
  is absent. Loading a stored graph revalidates all members/edges; it cannot silently drop missing refs.
- `listSkillsInDomain(domainId)`: all published historical definition versions, including RETIRED,
  ordered by stable ID then version. No automatic latest/ACTIVE selection or proficiency filtering.
- `publishDefinition(definition)` and `publishGraph(graph)`: atomic publication. Exact replay is
  idempotent; changed content under an existing identity/version is rejected. Graph definitions must
  already exist with exactly matching content. Definitions may be published independently of a graph.
- Connection-scoped publication overloads for migration/composite transactions: caller must enable
  SQLite foreign keys **before** starting the transaction, and commit/rollback it. Repository checks
  both requirements; it never commits caller-owned work. All exceptions require caller rollback.

Once loaded, `SkillGraph` provides direct `prerequisitesOf(ref)`, direct `dependentsOf(ref)`,
`topologicalOrder()`, and transitive `prerequisiteTraversal(ref)`. The last method returns only the
requested skill's ancestors, prerequisites first, excluding the requested skill and unrelated nodes.
Unknown/non-member versions throw an actionable `IllegalArgumentException` rather than an empty
partial answer. Kahn traversal uses a priority queue ordered by stable ID/version, so insertion order
never affects ties. Input lists and returned lists cannot be mutated.

Example for future evidence/quest integration (after normal database initialization):

```java
var catalog = new SkillCatalogRepository();
var atomic = catalog.findSkill(new SkillRef("java.concurrency.atomic-update", 1)).orElseThrow();
var graph = catalog.findGraph(new GraphRef("java-debugging-concurrency", 1)).orElseThrow();
var prerequisites = graph.prerequisiteTraversal(atomic.ref());
var dependents = graph.dependentsOf(atomic.ref());
```

The results describe definitions, not which prerequisites a particular learner has demonstrated.
New domains use the same records/repository with different stable IDs and content; no core Java enum
or domain-specific validator is required. Graphs can contain cross-domain edges when explicitly curated.

## Persistence and migration

Migration **13** was allocated after inspecting `main` at `9a25a05` (head was migration 12).
[SkillCatalogSchema](../../src/main/java/com/codefit/config/SkillCatalogSchema.java) creates five new tables:

| Table | Keys / role |
| --- | --- |
| `evolution_skill_definitions` | `(skill_id, definition_version)`; domain, name, description, lifecycle, publication flag |
| `evolution_skill_behaviors` | `(skill_id, definition_version, position)`; ordered original behavior text; unique behavior per definition |
| `evolution_skill_graphs` | `(graph_id, graph_version)`; publication flag |
| `evolution_graph_skills` | `(graph_id, graph_version, skill_id)`; exact definition version with composite FK to definition; one version of a skill per graph |
| `evolution_skill_prerequisites` | `(graph_id, graph_version, prerequisite_skill_id, dependent_skill_id)`; exact endpoint versions, both composite FKs to members of that same graph |

No existing table/column/row is rewritten. A domain index supports catalog queries. There are no new
runtime dependencies. Graph relationships and definition versions remain independently versioned.

Publication flags are internal transaction state, not editable drafts or learner state. A definition is
inserted with its behavior rows and sealed; a graph is inserted with all members/edges and sealed.
Only sealed versions are returned by queries. Published headers, behaviors, graph members and edges
have update/delete/append guards. Database constraints reject invalid versions, duplicate edges,
self-dependencies and mismatched endpoint references. Publication guards check nonempty/dense
behaviors, nonempty graphs and published definitions; a recursive SQL check also rejects graph cycles.
No supported operation edits or deletes a published version. Alterations require a new version.

The repository opens its own FK-enabled connection and transaction for ordinary publication.
Insert-if-absent acquires the SQLite write lock before comparing an existing version, so concurrent
publishers serialize: identical content succeeds idempotently, conflicting content gets one winner.
The existing 5-second busy timeout still applies; storage contention/errors surface to callers and
are not silently retried or treated as success. One transaction contains the complete version, with
rollback on write, validation or commit failure. The connection is closed without switching failed
transactions back to auto-commit. Caller-scoped overloads enforce the same preconditions.

Migration 13 runs schema creation and seed publication in the existing per-migration transaction.
Domain-validation exceptions in this migration are translated to SQL exceptions so the existing
migration runner rolls back partial DDL as well as data. Failed migration leaves no recorded version
and is retryable after resolving its cause. Migration numbers must be rechecked against current main
before merging if concurrent P2P work advances the head.

## Initial curated taxonomy

[JavaDebuggingSkillSeed](../../src/main/java/com/codefit/config/JavaDebuggingSkillSeed.java) seeds exactly
four ACTIVE version-1 skills in domain `software-engineering`, graph `java-debugging-concurrency` v1:

`java.debugging.reproduce → java.concurrency.atomic-update → java.concurrency.invariant → java.concurrency.liveness`

Names, descriptions and observable behaviors come from the approved
[Java Debugging & Concurrency slice](java-debugging-concurrency.md#goal-and-minimal-skill-graph).
Each initial definition has that complete observable-behavior statement. The seed has no learner
progress, tasks or external content. Seeding uses the same publication path and immutable conflict
checks as normal writes; it does not use replacing upserts. Reinitialization does not duplicate it.
Retired versions may remain members of historical or explicitly curated graphs; consumers decide
eligibility later using pinned refs, never by changing historical definitions.

## Verification and self-review

Tests added:

- [SkillGraphTest](../../src/test/java/com/codefit/evolution/SkillGraphTest.java): chains, diamond and
  disconnected traversal, insertion-order independence, cycles, unknown/mismatched refs, duplicate
  edges/definitions, mixed versions, self-dependencies, invalid contracts, immutable collections and
  retired/cross-domain references.
- [SkillCatalogRepositoryTest](../../src/test/java/com/codefit/repository/SkillCatalogRepositoryTest.java):
  exact seed/round-trip, multiline behaviors, definition/graph conflicts, retirement/history, source
  lookup, rollback after injected behavior/edge failures, SQL immutability/referential/cycle guards,
  caller transaction preconditions and concurrent identical/conflicting publishers.
- [SkillCatalogMigrationTest](../../src/test/java/com/codefit/config/SkillCatalogMigrationTest.java):
  populated migration-12 upgrade, all original-table row comparisons, repeat startup/seed and reopened
  connections, legacy null preservation by the migration, failure rollback/version tracking and retry.

All database tests use throwaway databases through `IsolatedDatabaseExtension` with its resource lock;
the root learner database remains protected. The migration fixture preserves existing cards,
review history, imported problem progress/attempts/order, drafts, sessions, assessment results, settings
and peer tables. Existing initializer behavior is tested honestly: it already fills NULL accepted answers
from card backs at startup. Migration 13 itself preserves even those nulls; the repeated-startup fixture
uses normal initialized card values. That pre-existing normalization is not changed by EV-02.

Focused and full suite results are recorded when complete below. Self-review boundaries:

| Concern | Decision / verification |
| --- | --- |
| Data integrity | Exact refs, FK-enabled publication, model validation, database constraints/triggers and read-time revalidation. No inferred mastery or implicit latest refs. |
| Transaction safety | One complete publication per transaction, caller-owned overloads documented, injected failures roll back, duplicate/concurrent writes tested. |
| Historical stability | No mutation API; identical replay only; separate definition and graph versions; retirement is a new definition. |
| Migration compatibility | Additive migration, rollback/retry, populated upgrade and unchanged original-row snapshots; no P2P protocol/schema rewrites. |
| Domain independence | Pure records/queries accept any valid domain ID; Java taxonomy is only config seed content. |
| Scope | No evidence, estimates, category mappings, rewards, evaluator, recommendation, UI or sharing added. |

## #210 acceptance mapping

| Criterion | Implemented proof |
| --- | --- |
| Seeded minimal graph and testable behaviors | Four exact curated definitions/three edges; seed, repository and traversal assertions. |
| Cycles and unknown references rejected | Constructor validation plus composite FKs and publication cycle guards; missing/version/cycle SQL and unit cases. |
| Deterministic traversal | Stable canonical lists and priority-queue topological order; shuffled diamond/disconnected fixtures. |
| Existing databases upgrade without study resets | Migration-12 populated fixture, original-table row comparisons, repeated initialization and failed-migration rollback/retry. |
| Isolated persistence tests | Extension/resource lock on both new database suites; no root learner database access. |
| No mastery inferred from taxonomy | Definitions, refs and edges only; no learner state or writes to existing mastery/XP/curriculum code. |

## Limits and next work

Definitions and graphs are local curated metadata, not anti-tamper certification. Direct external
SQLite tampering that disables integrity protections is outside the supported publication contract;
no database encryption or remote graph ingestion is introduced. No "latest" pointer, graph editor,
content importer or automatic migration of historical refs is needed for this slice. Version gaps are
allowed; each published version is explicit and immutable.

Next: #211 can reference `SkillRef` and the exact graph versions to persist provenance-aware evidence
and explicit category mappings. #213 can pin challenge targets/prerequisites to the same refs. #212
and #215 later consume those definitions alongside evidence; neither may infer proficiency from
catalog membership or prerequisite reachability alone.
