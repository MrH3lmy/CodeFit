# Resume from CodeFit Knowledge Compiler v0.28

1. Use the private `codefit_knowledge_compiler_checkpoint_v0_28_2026-10-18.zip`, not individual GitHub handoff files, as the full authoritative source-free checkpoint. Inspect `output/v28_package_manifest.json`; verify CRC and SHA-256 for each listed entry.
2. Private source PDFs must remain outside archives/public GitHub. Their metadata/fingerprints remain in the inherited workspace.
3. Review v0.28 code: `review_intake_ledger_v28.py`, `tests/test_review_intake_ledger_v28.py`, `REVIEW_INTAKE_LEDGER_PROTOCOL_v0.28.md`.
4. Run `python -m unittest tests.test_review_intake_ledger_v28 -q` and bounded inherited suites with `python verify_all_modules_v28.py START END`; inspect `output/deep_v28/regression_summary_v0.28.json` (537/537, 32 modules).
5. GitHub issue #253 coordinates independent reviewers. Do not fabricate reviewer identities, keys, decisions, mastery scores or an isolated execution worker.
6. Next meaningful milestone: actual independently signed evidence and owner adjudication, plus separately approved Java worker security. If unavailable, implement a distinct independently useful pilot-blocker fix rather than endlessly adding synthetic tests.
