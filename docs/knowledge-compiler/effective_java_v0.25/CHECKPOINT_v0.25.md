# CodeFit Knowledge Compiler v0.25 — 2026-10-18

**Parent:** v0.24 verified private checkpoint; older versions are historical.

## Bounded substantive new work

- New original `worker_receipt_v25.py` accepts only **operator-key-pinned, domain-separated Ed25519 signed receipts** bound to a prepared v0.24 Java submission and policy fingerprint. It enforces strict protocol, telemetry ceilings, coherent exit/test/status, timestamp and replay-state checks.
- It returns a frozen, explicitly **quarantined and non-mastering** result, even if the signer claims `TESTS_PASSED`.
- New `tests/test_worker_receipt_v25.py`: **68/68** synthetic tests, ephemeral signer in memory, no trust anchor shipped. No actual sandbox present or learner code executed.
- `verify_all_modules_v25.py` and `finalize_v25.py` capture per-module bounded run results and validate **448/448** Python tests across **29/29** modules, zero skips or failures.
- Added security contract, external-worker handoff and pending release-gates documents. No publisher excerpts or private source PDFs added.

## Counts and evidence (do not double count)

| Measure | v0.24 | v0.25 |
| --- | ---: | ---: |
| Deep Effective Java first-pass author notes | 90/90 | 90/90 |
| Five-book major units discovered | 504 | 504 |
| Major units with any draft mapping | 157 | 157 |
| Unmapped major units | 347 | 347 |
| Distinct draft mission slots | 419 | 419 |
| Replaced Effective Java overlays | 360 | 360 |
| Python tests passing | 380/380 | **448/448** |
| Python test modules | 28 | **29** |
| Historical Java21 assertion fixtures | 1,150 | 1,150 |
| Historical javac negative cases | 242 | 242 |
| Independent technical/pedagogical approvals | 0 | 0 |
| Externally approved isolated Java workers | 0 | 0 |
| Production learner code graders / verified mastery | 0 | 0 |

These measures have different meanings. **90/90 author drafts is not 90/90 approved lessons; 157/504 mappings are not 157 complete educational units**. No invented overall percentage of finished learning product is asserted.

## Release boundary

Signed receipts do not prove worker isolation or honesty. Real secure execution, external technical/pedagogical adjudication, held-out learner grading and user authorization remain absent. No CodeFit reward, mastery, XP or production behavior touched. Branch remains unmerged.
