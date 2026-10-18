# CodeFit v0.26 — Local quarantine ledger protocol

**Scope:** Author-built offline prototype, not a production grader or isolation layer.

## Boundary and flow
1. An owner-controlled caller creates a private `0700` directory on a trusted local filesystem. The ledger file must be an absolute `.sqlite3` path, owned by that caller and `0600` after creation.
2. The operator independently obtains a worker public key and separately pinned SHA-256 fingerprint, and initializes the ledger with a fixed worker identity and the existing v0.24 policy digest. No enrolled trusted worker is provided by this release.
3. The caller obtains an original `PreparedAttempt` from `grader_boundary_v24.prepare_attempt` and a signed v0.25 worker receipt. No Java source is run by this component.
4. `accept(...)` uses a separate SQLite connection and `BEGIN IMMEDIATE` to serialize concurrent writers. It verifies that the stored trust metadata still matches the expected worker, pinned key and policy, checks duplicate nonce/attempt/work order, verifies receipt signature and fields through `worker_receipt_v25.verify_receipt`, and inserts only hashes and small status metadata under unique constraints.
5. If an insert or validation fails, no new receipt becomes accepted. Successful records are **quarantined**; all grading/mastery/rewards/release gates remain false.
6. `audit_snapshot()` reports a count and false release gates, not learner code or signing secrets.

## Tested behavior
- **34/34** synthetic SQLite ledger tests, including duplicate-after-restart, thread races, independent OS-process races, key rotation, schema tampering, forged signatures, invalid JSON, failed work-order binding and private-file checks.
- **482/482** Python tests across 30 modules including inherited v0.25 regressions. All processes operated on author fixtures; no learner-source execution.
- Only a single local database file is shared during these concurrency tests. There is no distributed coordination.

## Explicit limitations and operator obligations
- This is a **single-host, single-filesystem** ledger, not a distributed replay service. Deploying replicas against independent DB files destroys global replay protection.
- A rollback/restore of the SQLite file can reintroduce old nonces. An externally anchored monotonic log, backup provenance and disaster recovery must be designed separately.
- A signer key proves authenticity of the signed receipt relative to the enrolled public key; it does **not** prove truthful execution, kernel isolation, correct telemetry, or educational accuracy.
- File permissions and a private directory mitigate accidental exposure; this is not a defense against root, the filesystem owner, malicious administrator, same-UID process, symlink races in hostile ancestor directories, or a compromised host.
- This component does not independently enroll reviewers/workers, store confidential answers, issue learner-facing grades, control rate limits/quotas, authorize code execution, provide centralized audit/tracing, or satisfy compliance requirements.
- Database schema and pinned worker changes fail closed. Secure key rotation needs an owner-approved migration with separate evidence and trust root.
- Keep real receipts and ledger DB private. This repository and GitHub handoff contain only synthetic test data and source code. No publisher PDFs, hidden rubric keys or signing secrets.
