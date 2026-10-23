# Ascension Tree: pre-implementation audit

Inspected baseline `febc92aad4a486b72dfb836e9a1ad7ca0487b9eb` on the isolated
`feat/gate2-ascension-evidence` worktree. No learner database was opened. The design
uses the already authorized, frozen learner-snapshot capability; synthetic databases
are the only verification inputs.

## Ownership and read boundaries

| Owner | Finding | Decision |
| --- | --- | --- |
| `SkillCatalogRepository.findGraph(Connection, GraphRef)` | Pure connection-scoped read, followed by `SkillGraph` validation; materialization itself has no size bounds. Exact versions and directed prerequisites are authoritative. | Read the existing Java pilot graph reference, with SQL count/type/text bounds before materialization. No copied seed graph or implicit latest-version policy. Preserve exact skill versions, lifecycle and behavior order. |
| `EstimateRevisionRepository` | Stores immutable canonical inputs, not an output column. `find`/`history` decode, verify checksum and identity, then replay `ordinal-v1`; they open `DatabaseConfig` connections. | Use the existing codec and policy on the caller's frozen connection. Verify bounds before fetching the blob, checksum, canonical decode, row identity and the entire exact skill definition. Do not open the process-global database. |
| `MasteryEstimateService.estimate/pilot/publish/reset` | Can write revisions or epochs. `capturePilot` is read-only, but traverses evidence, source/evaluator owners and exposure history; ordinary owners fetch full private submission snapshots internally. | Do not invoke these methods. Replay only a saved metadata-only input; no new estimate, epoch, selection or publication. |
| `EvidenceRepository.forSkill` | Revalidates source facts, correction heads, retractions, mapping supersession and evaluation aliases. These are all necessary for *current* eligibility. | Do not infer current eligibility from the historical blob or from a source head alone. Read only bounded lifecycle headers to flag known withdrawal/change/missing facts; absence of a warning is not validation. |
| `OrdinalMasteryPolicy` | Pure Java policy. Stale and contradicted observations cannot establish advancement; assistance, novelty, behavioral scope, source aliasing, epoch and retained exposure context matter. | Reuse the pinned policy to reproduce the recorded interpretation. Expose band, support, freshness and reasons explicitly **at the saved as-of time**. Do not substitute capture time or renderer calculations. |
| `AdaptiveQuestService` | Recommendation captures and publishes decisions/estimates and may reconcile mastery rewards; response/start records learner choices and exposure. | No recommendation, eligibility, unlock, or next-mission operation. A prerequisite edge is a curriculum relationship, not an unlock. |
| `PlayerSystemService.overview` | Calls adaptive recommendation and effort reward reconciliation; baseline status can derive estimates. Evidence/submission convenience reads use writer transactions. | Do not reuse the JavaFX composition service for the snapshot screen. |
| `LearnerSnapshot` | Consistent online backup, private store, selected-source consent, read-only connections, explicit generation/profile identity, redacted failures. | Reuse this boundary unchanged for source ownership. Add a separate finite `learner.skills.read` projection on the completed frozen backup. No migration or recovery writer. |

References: [mastery semantics](../../evolution/mastery-estimation.md),
[versioned graph](../../evolution/skill-graph.md),
[existing JavaFX composition](../../evolution/player-system-ui.md),
[learner snapshot ADR](../adr-0003-learner-snapshots.md),
[preceding read audit](../gate2-profile/audit.md).

## Historical support and invalidation

Saved `INDEPENDENT`, `TRANSFER_SUPPORTED`, or `RETENTION_SUPPORTED` can survive a
later source withdrawal by design: revision replay answers what the earlier input
supported. It does not say that the backing source remains valid. Likewise, a saved
`CURRENT` freshness value belongs to its own `asOf`, not the current wall clock or
capture date. A reset can invalidate the epoch without deleting saved history.

Every node therefore carries `currentSupport: NOT_EVALUATED`. No current-capability
boolean, unlock flag, score, XP proxy or prerequisite satisfaction is returned.
Known lifecycle changes yield explicit warnings: `EVIDENCE_WITHDRAWN`,
`EVIDENCE_REVISED`, `EVIDENCE_MISSING`, `MAPPING_CHANGED`, `EPOCH_CHANGED`.
These warnings are incomplete by intent: validating unchanged source facts and
durable evaluation context would require the owners' full current-input path.

Select the greatest saved revision ID for the exact graph and stable skill ID;
verify the exact definition version before replay. A mismatched latest revision
is unavailable, never replaced by an older positive result. Unknown policy,
checksum corruption, noncanonical bytes, invalid metadata or over-limit input
also become unavailable. A missing revision is a distinct state, not an invented
UNKNOWN estimate. Missing/invalid graph returns an unavailable graph without
seed fallback. A published newer version is disclosed without selecting it.

## Bounded contract

The additive stdio v3 request is `learner.skills.read` with the existing exact
request envelope and 1,024-byte request bound. Result kind is `learner-skills`,
projection v1. Profile/session/capture/schema/read-only provenance is identical
to the overview. The skills frame is capped at 256 KiB; overview stays 8 KiB.

Bounds: 32 nodes, 128 edges, 16 behaviors per node, 128 UTF-16 units for every
reference/domain/epoch ID, 160 for names, 2,048 for descriptions, 1,024 per
behavior, 24,000 aggregate graph text units. Only printable text and ordinary
multiline whitespace are accepted for authored descriptions/behaviors. SQL
checks string storage type and byte length before JDBC reads the text.

Saved input blobs are at most 1 MiB each and 8 MiB total. The result identifies
only one latest revision per node; selection and bounds precede blob fetch.
Replay admits at most 512 observations, 512 activities, 64 mapping references,
and bounded check/target collections. The existing 32 MiB/10,000-item codec
limits remain unchanged for its original callers. The new projection imposes
stricter display limits and never publishes a partial positive interpretation.

No raw input blob, evidence payload, artifact hash, submission context, learner
answer/source/reflection, check specification, hidden test, failure diagnostic,
file path, peer key or arbitrary exception is exposed. Reasons and lifecycle
warnings use finite enums; evidence summaries use ordinal-policy counts and
bounded enum/count pairs. No user-facing freeform payload from an observation
is copied. All queries share one caller-owned read transaction.

## Required adversarial verification

Use synthetic fixtures for no saved estimate, independent support, insufficient
diversity, stale support, contradiction, withdrawn source, old epoch, version
mismatch, unsupported policy, corrupt bytes, excessive graph/blob/input, and
malformed catalog. Verify source and private backup bytes remain unchanged by
projection reads, global `DatabaseConfig` is untouched, no service initializes or
reconciles, no positive older revision fallback, and protected sentinel content
does not cross the DTO. Assert prerequisite topology from persisted graph data,
not UI seed order. Protocol checks must reject extra fields, methods, wrong
generation/profile identity, oversized replies and stale cross-profile results.

Current capability and adaptive prerequisite satisfaction cannot safely be
established by this bounded historical projection. Historical-only semantics
are the chosen product behavior, not a silent degraded claim.
