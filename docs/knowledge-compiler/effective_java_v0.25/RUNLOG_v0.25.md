# CodeFit Knowledge Compiler v0.25 — Run log

1. Verified the existing local v0.24 workspace and its resume/release-gate documents.
2. Implemented `worker_receipt_v25.py` using external key pinning, Ed25519 domain separation, exact source and policy digests, bounded telemetry, strict status coherence, time windows, nonce checks, no raw code/output and explicit `QUARANTINED` status.
3. Added 68 synthetic unittest cases using a generated ephemeral *test-only* signing key. First run found a test-helper duplicate-argument error; fixed the test helper and reran successfully: **68/68**. This was a test harness mistake, not a security bypass.
4. Ran each inherited Python module in separate bounded subprocesses; large grouped runs hit the tool time limit without diagnostic failures, so completed subsequent modules in smaller groups. Final persisted individual module evidence shows **29/29 modules PASS; 448/448 tests; zero skips**. File: `output/deep_v25/regression_summary_v0.25.json`.
5. No extra Java behavioral assertions or negative `javac` checks were introduced in v0.25. Historical totals carried without recounting.
6. No learner Java execution, external worker, independent reviewer enrollment or production changes were performed.
7. Packaged all scripts/data, full runlog and reports in a versioned resumable private ZIP; saved a copyright-safe public handoff separately to a new unmerged GitHub branch (destination status recorded in `FINAL_SYNC_STATUS_v0.25.md` after successful readbacks).
