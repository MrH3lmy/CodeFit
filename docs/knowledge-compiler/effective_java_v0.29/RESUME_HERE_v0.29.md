# Resume CodeFit Knowledge Compiler from v0.29

1. Restore/check `codefit_knowledge_compiler_checkpoint_v0_29_2026-10-19.zip`; verify `output/v29_package_manifest.json`, SHA-256 of every listed file and clean ZIP CRC. The v0.28 archive must remain unchanged.
2. Private preferred PDF is outside the ZIP at `/mnt/data/Joshua Bloch - Effective Java 3rd - text-native.pdf` (SHA-256 b1b606b212916d637203bbd1d0ff59bdfbde63e6d8271a6b88bd7bacd07bea5e). Restore private aliases if source tests need them, and keep both source PDFs outside public artifacts.
3. Read `REVIEW_ADJUDICATION_DOCKET_v0.29.md` and v0.28 intake protocol. Re-run `python -m unittest tests.test_review_docket_v29 -q` and bounded inherited suites with `python verify_all_modules_v29.py START END`.
4. Review Github issue #253 for owner-led reviewer enrollments and real signed evidence. **No signed decisions or independent approvals existed at v0.29.** Do not invent reviewers, sign decisions on their behalf, or close the issue prematurely.
5. Prioritize a genuinely independent reviewer action or separately anchored raw signed-envelope evidence vault. Never treat a local status or synthetic acceptance as release permission. Separate audited Java execution worker is also missing.
