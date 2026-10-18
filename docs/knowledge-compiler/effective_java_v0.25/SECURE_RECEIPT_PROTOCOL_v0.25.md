# CodeFit v0.25 — signed worker receipt admission (NON-PRODUCTION)

This iteration **does not execute learner Java**. It implements only a receipt verification boundary between a hypothetical future isolated worker and an offline adjudication process. A successful `verify_receipt` result is **quarantined**, not an approved grade.

## Exact protocol

- The v0.24 `prepare_attempt` stage computes a SHA-256 work-order digest over the validated submission and immutable worker policy. It holds no learner-source material in the returned prepared result.
- An isolated worker *in a future separately deployed environment* would create a signed receipt with worker identity, attempt and mission IDs, exact work-order and policy digests, a fresh nonce, bounded execution telemetry, output **digests only**, compile exit code, and internally coherent test counts/verdict.
- `worker_receipt_v25.verify_receipt` requires the caller to provide a **trusted worker public key, its SHA-256 pin, and the expected worker identity via an external owner-controlled trust channel**. It does not read a key from the submitted receipt and does not bundle trusted keys.
- The verifier checks an Ed25519 signature with domain separation; strict exact-key JSON schemas; duplicate-key and nonfinite-number rejection; time validity; nonce validity; size ceilings; exact work-order, mission and policy binding; status/exit/count coherence; and bounds from v0.24 policy.
- The returned immutable object has `status=CRYPTOGRAPHICALLY_VERIFIED_QUARANTINED` and explicitly false isolation approval, production grading, mastery, rewards, and learner release. `TESTS_PASSED` is only a **worker claim**, not a verified learner grade.

## Threats addressed by this author prototype

1. Edited status or telemetry breaks the signature.
2. Wrong source/work-order or policy hash is rejected even with a valid signature.
3. Wrong public key/pin or worker identity is rejected.
4. Oversized, malformed, type-confused, duplicate-key, noncanonical-signature inputs fail closed.
5. Contradictory claimed success/failure/test counts fail closed.
6. Replay protection works *only* when the operator supplies previously stored nonces. Batch-local duplicates are rejected; **no durable replay store exists here**.
7. Raw standard output, standard error, hidden tests, publisher text and learner source are not included in the returned receipt.

## Explicit limitations and remaining release gates

A signature **cannot prove** that an isolated environment was used, telemetry is truthful, tests are hidden, code was not tampered with, test criteria are fair, or the signer was independently enrolled. An attacker controlling a supposedly trusted worker could sign fabricated measurements. Caller-controlled pins without an external trust process are self-attestation and provide no assurance. The clock/freshness and nonce check require a trustworthy orchestrator and durable transactional replay ledger in production. The receipt is not independently reproducible without a separate audit protocol.

The future execution service requires sandboxed file and process trees, a read-only JDK, network denial, strict cgroups/VM limits, separate test artifacts, process teardown, worker identity enrollment/rotation, independent security red-team, held-out correctness studies, and a human-authorized pilot gate. **No worker has been independently approved, no learner code was executed, and no XP or mastery state may be updated.**

## Deterministic author validation

`tests/test_worker_receipt_v25.py` creates an ephemeral signing key in memory and tests 68 synthetically generated cases. The key is neither a trusted production identity nor stored in any checkpoint. `finalize_v25.py` checks all 29 separately executed Python modules and records 448 passing tests. Historical 1,150 Java21 assertion checks and 242 compiler rejections are carried forward unchanged, not recounted as new work.
