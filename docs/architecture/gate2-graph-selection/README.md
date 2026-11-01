# Explicit graph selection and lifecycle (#222)

## Audit before implementation

Baseline: `79adc347681221599e70003b40fdf45685155dc0` (merged #284). Open PR #255 changes the native JavaFX shell; this slice does not touch those files.

| Concern | Actual owner and side effects | Decision |
|---|---|---|
| Immutable taxonomy | `SkillCatalogRepository` publishes exact `GraphRef`/`SkillRef` versions in caller/owned transactions. `SkillCatalogSchema` seals headers/members/behaviors/edges. `SkillGraph` validates exact endpoints and cycles. `SkillLifecycle` is immutable definition metadata. No graph selection exists. | Reuse publication; add a separate selection/lifecycle repository, never change catalog rows. |
| Evidence mapping | `EvidenceMappingRepository` explicitly publishes contiguous mapping lineage versions. Mapping refs pin graph and exact skills; publication does not reinterpret observations. | Inspect exact mapping records only. Selection never publishes/remaps evidence. |
| Quest/evaluation | `QuestCatalogRepository` validates exact graph/targets, manifests, assets and backing. Presentations commit exposure. Evaluator owns frozen submission/attempt/evidence effects. | Keep these contracts pinned. Gate only new desktop practice openings; existing authorized presentations remain resumable. |
| Mastery | Generic `estimate` can derive/publish with explicit mappings; `pilot`, `capturePilot`, `PracticeSkillsService` deliberately pin Java v1. Desktop preflight currently rejects any published v2, even when unused. | Remove that false auto-upgrade assumption. Check explicit selection and supported pilot instead. Capture/publish engine stays unchanged. |
| Adaptation/rewards | `AdaptiveQuestService.recommendCurrent` publishes decisions and reconciles milestones. `AdaptationInputReader` has exact pilot manifest/equivalence assumptions. `PlayerSystemService.overview` mutates learning state. | Never call from catalog reads or selection. No new graph adapter is enabled. |
| Historical displays | `PracticeAnalytics`, `LearnerSkillProjection`, debriefs and estimate receipts read exact Java v1 and saved policy refs. | Preserve exact historical projections; do not relabel them with the active selection or merge another graph into them. |
| Ownership/schema | `ExperimentalProfile` verifies marker, owner lock, integrity and base schema 25. Optional desktop draft/submission/skills schemas install only on this disposable profile. JavaFX real profiles do not use these schemas. | Add a versioned optional schema only under this existing exclusive Linux owner. No global migration or real-profile write path. |
| JavaFX | Baseline, Player System, progression and adaptation intentionally use the installed pilot. | Keep the legacy JavaFX pilot track unchanged. The new explicit **experimental engineering track** is a separate consumer of the common catalog; do not silently apply desktop selection to JavaFX. |

## Selection ownership and approval boundary

One exact selected graph per supported experimental engineering track, persisted by Java. A new graph never changes that pointer. Selection is planning/inspection intent, not content approval, an assessment adapter, an evidence mapping, or mastery. An ACTIVE published software-engineering graph may be selected while its estimator and practice adapters remain unavailable. Only the existing exact bundled pilot has an executable desktop adapter in this release.

Missing, malformed or retired selections remain explicitly unavailable; never fall back to v1 or MAX(version). The initial explicit installed default is v1, created only with the optional disposable-profile schema. Historical records and graph versions remain intact. Reads never initialize selection, publish estimates, reconcile rewards or create exposure.

Graph retirement/replacement is append-only lifecycle metadata separate from immutable graph/definition publication. Curator repository calls require a caller-owned transaction; there is no renderer retirement/publication/approval command. Retired graphs remain inspectable; rollback requires an eligible exact target. No fabricated reviewer approval is recorded. Content review is NOT_RECORDED unless a future independent approval contract provides it.

## Intended durability contract

Finite list/read/select/status capabilities on the existing supervised Java process, exact profile/session binding and bounded manual DTOs. Selection uses expected revision CAS under a short SQLite writer transaction. A durable immutable command receipt commits atomically with the selection. Main persists only bounded identifiers before dispatch. Ambiguous replies check the same command first; explicit retry retains its graph, expected revision and original command identity. No automatic replacement intent, no lost-byte problem (the entire payload is metadata), no writes during status/catalog reads.

Synthetic v1/v2 and independent graph fixtures belong only to tests, never production seed assets. A future curated graph needs reviewed definitions/behaviors/prerequisites, provenance, exact mappings, compatible assessments, independent approval and explicit release gates. #211/#213/#215 retain evidence, challenge and adaptation ownership; this slice does not implement those policies again.

## Protocol v1

The finite owning-frame IPC channel `codefit:practice:graphs` delegates to `PracticeOwner.graphAction`. Renderer actions are `{kind:read,graphId,graphVersion}`, `{kind:select,graphId,graphVersion,selectionRevision}`, `{kind:status}` and `{kind:retry}`, with exact profile/session arguments. Empty graph ID with version zero means inspect the selected exact reference, never choose latest. All other references require a positive integer version.

Java operations `practice.graphs.read/select/status` use the existing private version-1 framed protocol. Main generates and durably journals command/origin identities for selection. A response carries either a read-only `view` or an immutable `receipt`; main alone attaches pending recovery metadata. Strict Zod schemas in `desktop/src/graph-contract.ts` and manual Java serialization in `PracticeGraphProtocol` define the exact fields. Unknown fields and mismatched envelope identities fail closed. Graph definitions are public catalog metadata; no challenge, answer, evidence payload, draft, evaluator or P2P material is queried.

A view contains capture UTC, track, exact selected reference and revision, lifecycle/compatibility summaries, inspected reference, exact definitions/behaviors and prerequisite edges. Mapping availability means exact published mapping records, not independent approval. Content review is `NOT_RECORDED`: the catalog has no independent approval ledger. The only supported estimator/practice adapter remains the exact installed Java pilot with compatible mappings. Inspection is bounded to 32 versions, 64 definitions per graph, eight behaviors per definition and 256 edges; metadata sizes are checked before bulk hydration.

## Concrete lifecycle example (test fixtures only)

1. Publish pilot v2 and an independent synthetic engineering graph using the existing immutable catalog repository. Selection remains pilot v1 revision 0; saved v1 estimates and mapping refs remain unchanged.
2. Explicitly choose v2 with expected revision 0. Java commits selection revision 1 and its receipt atomically. V2 is inspectable but its estimator/practice are unavailable; no v1 evidence is remapped.
3. Curator appends RETIRED metadata for v2 with an optional exact replacement. Selection stays v2 and becomes unavailable; historical definitions remain readable. Replacement is explanatory metadata, not automatic migration.
4. Explicitly roll back to eligible v1 at expected revision 1. Revision 2 records the rollback. The existing pilot adapter becomes available again. No XP, estimate or historical evidence changes result from selection.
5. A stale command expecting revision 0 is durably rejected. Replay returns that original rejection even if later state changes. A command ID reused with a different target is rejected as a conflict.

Retirement and reactivation are curator-only repository transactions with lifecycle revision CAS. Definition retirement remains part of immutable definition publication; a retired member prevents active selection. Publication, replacement and retirement are not exposed as desktop commands. Missing prerequisites are rejected by `SkillGraph`/catalog publication, not guessed during selection. Missing versions never fall back.

## Transactions, recovery and compatibility

The optional `PracticeGraphSchema` installs only after exclusive ownership and integrity validation of the marked Linux disposable profile. Base schema version 25 and `SchemaMigrator` are unchanged. Tables store track selection, immutable receipts and append-only lifecycle metadata. No parallel evidence, draft, attempt or mastery storage is introduced.

Selection obtains SQLite writer ownership, checks original receipt, validates expected selection revision and exact target eligibility, then commits pointer and receipt together. No execution or expensive policy calculation occurs inside this transaction. Concurrent commands cannot both advance the same revision. A crash before commit leaves neither effect; a crash after commit is recovered by status. Explicit retry checks status first and retains original target, expected revision and command. Disconnect invalidates renderer responses without pretending to undo committed effects.

Existing authorized presentations remain pinned and resumable after selection changes. New desktop openings and explicit skill reconciliation require the selected supported pilot. Legacy JavaFX remains on its existing pilot track; historical Ascension/Analytics displays retain exact v1 semantics. Real-profile writers and non-Linux ownership remain disabled.

## Follow-up curated domain release

A new production graph requires independently reviewed immutable skill definitions, testable observable behaviors, prerequisite validation, original/source/license provenance, exact evidence mappings, an explicitly supported assessment/estimation adapter, independent approval records and a release gate. Selection alone supplies none of these. Knowledge Compiler v0.41 remains an unreviewed, non-executable preview; its stage IDs cannot be selected as published graph records or used as challenges.

The next curated-domain PR should publish one small reviewed graph and its explicit mapping/adapter compatibility, rather than speculative catalogs. Multi-graph historical analytics and snapshot projections need a separate reviewed contract before generalizing the current pilot displays.

## Verification in progress

Focused Java selection and existing reconciliation tests passed (19 tests before adding the concurrent-CAS case). TypeScript/Vite/Electron build, Linux packaging and production dependency audit passed (zero audit vulnerabilities). A real-Java Node protocol test caught an overly restrictive zero-version read sentinel; the corrected focused test passes.

Full-suite and packaged visual verification remain in progress. The first packaged pass ran alongside the full Java/Node suites and failed existing analytics connection deadlines; this is not accepted as a green run. Re-run serially after rebuilding the corrected contract. Screenshots, final counts, final adversarial review and GitHub CI are not yet complete. No PR should be represented as ready from these intermediate results.

### Timeout investigation (2026-11-01)

The completed full Java run reports 2,013 tests, zero failures/errors/skips. The subsequent focused run including concurrent selection CAS passes. The old Xvfb process was defunct with a stale display socket; a fresh display was established. On that display a bounded diagnostic wrapper around the existing connect handler recorded `4277 READY` (milliseconds, status only), with no profile, source or paths logged. The initial parallel verification run exceeded analytics' five-second UI assertion while native startup was still pending. Serial verification is required before attributing every prior timeout to contention. No production deadlines or security checks have been relaxed.

The new packaged graph test initially used an ambiguous `role=status` locator shared by the navigation announcer and receipt. It now selects the receipt status explicitly. The actual Java selection operation had returned UNCHANGED correctly; this was a test locator failure, not failed persistence.

### Adversarial review

Selection writes are restricted to the exclusive marked disposable-profile owner and exact main-frame IPC. A frozen snapshot is never write authorization. Main creates command identities; Java binds profile, origin, exact target and expected selection revision. Receipt lookup compares all payload fields before replay. SQLite writer serialization plus revision CAS prevents competing successful updates. A maximum safe revision cannot be incremented into an unrepresentable response.

Catalog retirement cannot relabel old graphs or alter historical evidence. Replacement is an exact optional reference, never a fallback. Existing mapping versions are inspected by exact graph; selected v2 has no v1 adapter and cannot publish v1 estimates. New openings are unavailable on unsupported selections, while acknowledged presentation replay retains its original exposure. No curator publication, approval or lifecycle-write IPC exists.

Read operations use query-only connections and explicit coherent transactions. Catalog reads are bounded before hydration. The renderer receives only catalog metadata, selection receipts and status, never source/evidence contents or assessment assets. Frontend responses are fenced by component generation and exact profile/session. Disconnect clears visible metadata and pending confirmation. A late acknowledgement cannot reattach a previous session; a committed write remains recoverable through its original journal identity.

Residual limits: selection currently belongs to one experimental engineering track; legacy JavaFX retains its installed pilot. Production retirement/approval tooling and general multi-graph estimation, adaptation and historical analytics remain separate work. Real-profile writes and Windows/macOS writers remain unavailable. Forced exit can leave a selection acknowledgement pending, but explicit status/retry uses the original durable intent.

## Packaged visual evidence

Actual packaged Linux Electron captures (published pilot only):

- [Dark desktop](screenshots/graphs-dark-1280.png)
- [Light desktop](screenshots/graphs-light-1280.png)
- [Dark narrow](screenshots/graphs-dark-760.png)
- [Light narrow](screenshots/graphs-light-760.png)

Serial packaged checks passed the previously failing analytics scenarios and graph inspection/selection. The complete suite remains in progress; intermediate screenshots and passed scenarios are not a substitute for final CI verification.

A serial draft test also hit its five-second startup assertion before reaching any draft authorization assertion. Connection helpers now wait for the actual ready UI using the existing 15-second supervised native-startup bound. This is scoped to native connection readiness; normal assertion, command, transport and security deadlines are unchanged. The ready state still requires successful Java authorization and catalogue initialization, not elapsed time. No sleep or unconditional test retry is introduced.
