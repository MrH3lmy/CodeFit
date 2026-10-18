# CodeFit Knowledge Compiler — v0.25 original-only agent handoff

This **unmerged research branch** documents a non-executing signed worker-receipt quarantine boundary. The authoritative resumable checkpoint (including the complete historical Python compiler, original authored notes and test evidence) is the **private** v0.25 ZIP in the existing CodeFit Knowledge Compiler Drive workspace. It does not contain publisher PDFs, book excerpts, purchased source code, answer keys or trusted worker signing identities.

## New work and exact tested claims

- `worker_receipt_v25.py` checks strict JSON schemas, signed Ed25519 receipts with domain separation and owner-controlled key pins, exact v0.24 prepared work-order and immutable policy hashes, bounded resource telemetry, consistent status/compile/test outcomes, timestamps and caller-supplied replay state. **It never executes submitted Java or declares mastery.**
- `tests/test_worker_receipt_v25.py` is provided as `test_worker_receipt_v25.py` alongside the modules, so the synthetic-only test suite can run with `python -m unittest test_worker_receipt_v25 -q`; 68/68 were observed passing. Signer keys are freshly generated within the tests and are **not enrolled** production keys.
- `grader_boundary_v24.py` is included for local test imports; its `execute_attempt` always refuses to launch learner Java.
- The full private checkpoint recorded **448/448 Python tests across 29 modules** (380 inherited + 68 new), zero skips; 1,150 historical Java21 assertions and 242 javac negative fixtures unchanged. There are 419 distinct original mission drafts, 360 replacement overlays, 90/90 first-pass Effective Java deep notes. None are independently approved.

## Release status

All synthetic or correctly signed results are **quarantined, not validated grades**. Signature possession does not prove secure execution, truthful telemetry, correct grading or external reviewer independence. Real audited isolation worker, trusted key enrollment, durable nonce ledger, independent source/pedagogy decisions, real held-out learner grading, and explicit product approval are still missing. Independently approved claims/missions: **0**; production graders: **0**; verified mastery: **0**.

No merge, CodeFit production progression, XP, rewards or mastery changes are authorized. See release gates and technical review queue for the next independent phase.
