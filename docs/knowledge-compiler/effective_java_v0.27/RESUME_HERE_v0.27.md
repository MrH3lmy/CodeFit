# Resume CodeFit Knowledge Compiler from v0.27

1. The complete private ZIP `codefit_knowledge_compiler_checkpoint_v0_27_2026-10-18.zip` is canonical. Inspect `output/v27_package_manifest.json` and verify ZIP CRC and every listed SHA-256.
2. Restore source PDFs **privately** from the existing owner-controlled drive, never add them to ZIP/GitHub. Text-native source fingerprint remains registered under existing `source_registry` records.
3. Run `python review_delivery_v27.py`; `python -m unittest tests.test_review_delivery_v27 -q`. Inspect `output/deep_v27/reviewer_delivery` for two separated packets and manifest.
4. All Python tests: run `python verify_all_modules_v27.py START END` in bounded groups, then read `output/deep_v27/module_test_*.json` and `regression_summary_v0.27.json`. The recorded pass count is **508 across 31 modules**.
5. Verify the public handoff contains no source PDFs, private answer keys, worker keys or learner submissions. Keep GitHub branch unmerged.
6. NEXT: actual independent reviewer enrollment and evidence collection; audited isolated Java worker and held-out learner validation. Do not counterfeit independent approvals.
