# CodeFit independent-review evidence intake — v0.28

## Status

**Author-side prototype, offline and quarantined. NOT independent reviewer sign-off and NOT production approval.** All original author content, 28 technical claims and six blind pedagogy missions, remain unapproved externally. No reviewer was enrolled in the project by this implementation.

## Changes from v0.27

- `review_intake_ledger_v28.py` is an owner-local SQLite evidence ledger. It accepts only Ed25519-signed technical or pedagogy decisions validated by the existing `review_attestation_v23.py`, with an externally supplied reviewer roster AND a separately pinned roster SHA-256. It never creates reviewer credentials or authorizes enrollment.
- It binds the ledger to the exact source subject hashes for the 28 claim and six mission review subjects. Replacing the roster or changing any subject makes the ledger refuse subsequent use until separately reviewed migration.
- `BEGIN IMMEDIATE`, SQLite uniqueness and transaction boundaries make replayed nonces, duplicate signed envelopes and repeat reviewer/role/subject decisions fail atomically. One invalid envelope rolls back the entire batch. Successful intake is still **quarantined**.
- `audit_snapshot()` reports only bounded aggregate counts, disagreements and release gates. It does not export reviewer IDs, raw evidence summaries, publisher text, answer keys, private keys, learner source, or signature material.
- The external review gate remains at ZERO: verifying a signature proves key possession, not reviewer independence, source correctness, teaching quality or authorized adjudication.

## Operator-controlled use (when actual independent reviewers exist)

1. Enroll independent reviewer identities and verify role, expertise and conflicts outside this compiler. Obtain their public Ed25519 keys through a trusted channel.
2. Place a complete owner-approved JSON roster in a private area, and record its SHA-256 **outside the review packet**. Never accept the pin from the submitted review itself.
3. Create a new owner-controlled directory with mode `0700` on a trusted *local* filesystem. Provide an absolute `<directory>/reviews.sqlite3` path; existing SQLite databases with unexpected or altered trust settings are refused.
4. Feed signed serialized decision envelopes, the out-of-band roster bytes/pin, and a timezone-aware clock to `ReviewIntakeLedger.accept_batch(...)`. Persist any signed evidence files in the owner's separate secured evidence vault; the ledger retains only hashes and bounded metadata.
5. Inspect only quarantine metrics and manually adjudicate the claims and blinded mission reviews. Revisions invalidate old approval decisions. Run a separate security review and release gate before any learner mastery is possible.

## Boundaries and residual risks

This is **not** an identity provider, human-adjudication engine, reviewer-enrollment service, secure worker or validated assessment. It cannot prove external identity or protect a compromised owner host. SQLite atomicity is local-filesystem scope only; multi-host/replicated replay safety, audit log sealing, backups, role revocation, source rotation, external evidence vault, key custody, and physical/file-system tampering require operational design and independent testing. Signature validity and author tests are insufficient for release. The ledger never runs learner code.

## Evidence

- New author tests: `tests/test_review_intake_ledger_v28.py`, **29/29 passed**, including restart replay, concurrent duplicate, bad signature/role, private-directory enforcement and transaction rollback.
- All inherited tests: **508/508 passed**.
- All 32 Python modules: **537/537 passed**, zero skipped or failed.
- Release: 0 independent approvals, 0 audited Java workers, 0 production graders, 0 verified learner mastery.
