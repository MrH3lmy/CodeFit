# v0.29 Private Review Adjudication Docket

## What actually exists

`review_docket_v29.py` is a **read-only owner-side** reconciliation of the v0.28 quarantined SQLite decision ledger against the pinned reviewer roster and the v0.22 source claim/mission snapshot. It enumerates **28 technical claims + 6 blind-pedagogy missions**. Every review subject has an exact content digest, current reviewer decision state, and an explicit owner checklist. Actual external signed decisions received so far: **0**.

The module returns a private owner-only docket with reviewer identities and evidence digests, plus a **separate redacted public summary** containing only aggregate counts. NEVER upload a nonempty owner-private docket, signed decision, authorized-source excerpt, credentials, or reviewer evidence to public GitHub or a publicly shared file.

## How the owner uses this when legitimate reviews arrive

1. Independently verify each reviewer’s real identity, credentials, conflicts and scope. Provide an authorized private reading path for each book source if lawful and required.
2. Pin the reviewer roster SHA-256 in a separate trusted channel; do not trust the submitter’s copy. Keep the received raw signed decisions and linked evidence in a separately secured owner evidence vault.
3. Use `ReviewIntakeLedger.accept_batch(...)` for atomic signed-decision quarantine, with persistent local replay protection. Then run `build_owner_docket(ledger)` in a private operator environment and inspect the **private** per-subject states.
4. For `AWAITING_EXTERNAL_SIGNED_REVIEW`, obtain an actual independent signed decision. For `AWAITING_OWNER_EVIDENCE_AND_INDEPENDENCE_ADJUDICATION`, verify full authorized source coverage, independent identity/conflicts, reproducible evidence and signed decision lineage, *then* record a separate written human adjudication. For `REVISION_OR_REJECTION_REQUIRES_AUTHOR_RESPONSE`, revise and rerun independent review. Disagreements require an owner arbitration and cannot be silently dropped.
5. Publish only the result of `public_progress(docket)` after owner privacy approval. Even then it is a **status report**, not a capability or release authorization.

## Important technical limitation discovered

v0.28’s ledger stores `signed_envelope_sha256`, `evidence_sha256`, and bounded decision fields, **not the signed envelope itself**. The v0.29 docket therefore cannot reverify an Ed25519 signature at readback time or detect a sophisticated owner-host compromise that rewrites stored rows into another internally consistent state. Full independently audited evidence needs a separately retained raw-signed-envelope vault, externally pinned append-only audit checkpoints, role revocation and source-revision rules. Do not label this project tamper-proof or human-approved.

## Verification scope

29 new author-only Python tests cover all pending subjects, roles, signed synthetic `ACCEPT/REJECT/NEEDS_REVISION` paths, private/public separation, private file ownership enforcement, malformed persisted row hashes/IDs, timestamp changes, altered roster/scope settings, and deterministic summaries. **They do not substitute for external reviewers**. In v0.29, all 566 Python tests across 33 modules passed, zero failures/skips.

Release gates remain **BLOCKED**: 0 real independent reviewers, 0 received external decisions, 0 owner adjudications, 0 production Java graders, 0 learner mastery verified.
