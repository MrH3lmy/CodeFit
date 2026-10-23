# Ascension read API v1

Executable definitions: [Java projection](../../../src/main/java/com/codefit/desktop/LearnerSkillProjection.java), [explicit serializer](../../../src/main/java/com/codefit/desktop/LearnerProtocol.java), [TypeScript schema](../../../desktop/src/skill-contract.ts), [transport](../../../desktop/electron/learner-session.ts).

## Capability and lifecycle

```ts
window.codefit.readLearnerSkills(): Promise<LearnerSkillsResult>;
```

Exactly zero arguments, owning WebContents/main frame/bundled entry only. No renderer-selected profile/graph/path/SQL. Main uses the existing consented snapshot's profile/session and rechecks generation after execution. No selected snapshot returns `UNAVAILABLE`; it never reconnects automatically. Disconnect/cancellation invalidates pending and queued requests and awaits process exit. React keys the view by session and checks profile/session/capture timestamp before rendering.

Additive private stdio v3 method `learner.skills.read` uses the original request envelope (`v`, UUID `id`, `method`, `profileId`, `sessionId`). Requests remain at most 1,024 bytes. Ready/overview frames retain 8,192 bytes; only an outstanding skills response allows 262,144 bytes. UTF-8, duplicate-key rejection, strict field validation, request correlation, expected response kind and exact identity checks apply. Errors reuse the existing redacted `LearnerError` vocabulary. Invalid frames terminate the session. SQL is deadline-bounded; the existing supervised process deadline and termination escalation remain active.

At most one request executes at a time. Concurrent overview callers share one promise; concurrent skill callers share a separate promise. The two distinct read kinds serialize. There is no arbitrary request queue or polling worker. Cancelling a queued read cannot start another process. Existing experimental v2 deck commands remain isolated.

## Projection

The success result has these explicitly serialized fields:

| Field | Meaning |
| --- | --- |
| `v:1`, `kind:"learner-skills"`, `source:"learner-snapshot"` | Version and provenance |
| `profileId`, `sessionId`, `capturedAt`, `schemaVersion:25`, `readOnly:true` | Same frozen identity as learner overview |
| `status`, `reason` | Available exact graph, or `GRAPH_UNAVAILABLE`, `GRAPH_INVALID`, `LIMIT_EXCEEDED` |
| `graph:{id,version}` | Java-owned pilot exact reference; null if unavailable |
| `newerGraphAvailable` | A newer published version exists, without selecting it |
| `nodes[]` | Exact `ref:{id,version}`, domainId, name, description, ordered behaviors, ACTIVE/RETIRED lifecycle, estimate |
| `edges[]` | Exact prerequisite/dependent refs from that graph |

Each node estimate includes `status:historical|missing|unavailable`; unavailable reasons are `REVISION_INVALID`, `POLICY_UNAVAILABLE`, `LIMIT_EXCEEDED`, or `GRAPH_MISMATCH`. No older positive fallback is permitted. A historical record includes revision ID, original asOf, policyVersion, epochId, band, historicalBand, support, freshness, contradicted, regressionCap, independentFamilies, supportedBehaviors indices, lastMeaningfulObservation, contributionCount, exclusionCount, provenanceCounts/assistanceCounts/exposureCounts (finite code/count pairs), finite reason codes, and lifecycle warnings. Missing/unavailable fields carry null/empty values, not manufactured UNKNOWN estimates.

`currentSupport` is always `NOT_EVALUATED`. All ordinal values and counts describe the saved interpretation at its original asOf. Lifecycle warnings (`EVIDENCE_WITHDRAWN`, `EVIDENCE_REVISED`, `EVIDENCE_MISSING`, `MAPPING_CHANGED`, `EPOCH_CHANGED`) report facts visible in the capture; no warning does not imply current validity. Current eligibility, unlocks and live freshness are absent. The UI maps finite codes to explanatory text, never recalculates a band.

## Bounds and interpretation

32 nodes, 128 edges, 16 behaviors/node; reference/domain/epoch IDs 128 UTF-16 units, name160, description2048, behavior1024, aggregate authored graph text24,000. SQL verifies counts/types/lengths before text or blob materialization. Graph constructors revalidate exact endpoints, version membership, duplicates and DAG structure. TypeScript independently validates topology and response limits before rendering.

Each latest saved metadata blob is at most 1MiB, with 8MiB aggregate replay budget. Replay admits at most512 observations/activities and64 mapping refs, with bounded nested metadata. Checksum, canonical encoding, policy version, row/graph/skill/epoch/asOf identity and exact published definition must match. Java reuses `EstimateCodec` and `OrdinalMasteryPolicy`; no policy is implemented in TypeScript. All read queries share one frozen transaction. Bounds are fail-closed rather than truncated positive support.

The DTO excludes original observation payloads, evidence IDs, submission IDs, artifact hashes, exact check definitions, private freeform diagnostics, answer keys, hidden cases, drafts and peer data. No generic object serializer is used. The historical metadata decoder runs only inside the bounded Java process; only explicitly permitted summaries leave it.
