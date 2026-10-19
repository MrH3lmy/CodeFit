# CodeFit Knowledge Compiler — v0.35 original-only technical content audit

**Author-assisted, internal adversarial review — NOT a genuinely independent external review. Unmerged branch; no production grader, deployment, XP, rewards, or mastery changes.**

## Scope and findings
- Independently re-executed author-authored Java21 challenge fixtures for Effective Java Items 19–25: **33 positive assertions and 12 expected compiler rejections**.
- Independently reran the author-authored v0.34 fixture: **26 positive assertions and six expected compiler rejections**.
- Verified the private licensed source's historical digest and seven Items19–25 source body hashes; did not publish, copy, or include any publisher passages.
- Individually screened **28 technical claims**: 23 have no contradiction identified in this bounded author-assisted pass, and **five need stronger reproducible evidence, tighter source classification, or more explicit API-documentation examples**.
- Editorially screened **six blinded-pedagogy subjects**: four need clearer task assumptions or fairer scoring instructions before a real independent blind review.
- New original deterministic Java21 concurrency counterexample for Item21: each primitive action is synchronized, but a default multi-step method permits the order `A, X, B`; the compound operation is not atomic. This demonstrates the gap without pretending an actual production race occurred.
- **12 new Python tests pass; 25 targeted tests including v0.34 pass.** Attempted full v0.35 rerun timed out after 10 passing modules (99 observed tests) and the known historical comparison-PDF skip. Latest completed available full Python regression remains **v0.34: 646 passed, 37 modules, one historical module skipped (nine tests)**. Do not report a complete v0.35 green suite.

## Major provenance warning
A matching SHA-256 of the historical `sliced_text` output does not prove strictly item-exclusive source coverage: TOC/page extraction can include headings or a next-chapter preamble. Modern sealed types/records, switch features and compiler-specific synthetic-field observations should be labeled Java21-era extensions rather than propositions written in the 2018 book. Do not silently mutate previously hashed claims.

## Storage and recoverability
Google Drive rejected v0.34 upload with 403 because the owner's **storage quota is exceeded**, confirmed by a Drive copy attempt. The existing private Drive folder and v0.33 remain untouched. Temporary **private ChatGPT Library recovery backups** of v0.34 and v0.35 were uploaded, downloaded and byte-level SHA-256 verified. See CHECKSUM.txt. Neither v0.34 nor v0.35 is a verified Google Drive upload.

## Counts and release gates
504 major units structurally discovered; 157 preliminary mappings; 90/90 Effective Java author drafts; 419 distinct original mission blueprints; 360 replacement overlays, not additive. Existing 28 technical + six pedagogy review subjects remain **0 authentic independent decisions, 0 external approvals, 0 human owner adjudications**. No production worker/grader, learner mastery, merge, or release authorized.

For actual reviewer enrollment and owner adjudication use issue #253; this branch is review coordination only. Never impersonate an external reviewer or fabricate signed evidence.

## Later storage restoration (2026-10-19)

The earlier Google Drive upload failure was caused by exhausted account storage. The owner freed space; the exact private v0.34 and v0.35 ZIPs are now uploaded into the original private Drive folder and have been downloaded and SHA-256/ZIP-integrity verified. Earlier statements in this handoff about blocked Drive upload are retained as historical incident evidence. This did not alter compiler data, review approvals, or branch merge state.
