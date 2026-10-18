# CodeFit Knowledge Compiler v0.27 — checkpoint

Parent: verified **v0.26** private ZIP and unmerged GitHub branch. 2026-10-18 Africa/Cairo.

## New work
- `review_delivery_v27.py`: deterministic author-side publication of independent review requests, **28** source-bound technical claim packets and **6** truly blind-from-author-rubric pedagogy requests.
- Each review request is bound to the complete v0.22 subject SHA-256 used by the existing v0.23 signed review protocol.
- `tests/test_review_delivery_v27.py`: **26** adversarial tests (missing/duplicate claims, forged approvals, secret fields, answer-key exposure, changed packet or manifest).
- `output/deep_v27/reviewer_delivery/` contains only original claim/prompt material and digests, no publisher passages, keys or hidden grading rubrics.

## Verification
**508/508 Python tests** passing across **31** individually recorded modules, 0 skips, 0 failures. 482 inherited + 26 new. Historical author-side Java 21 assertion count **1,150** and expected compiler rejection count **242** unchanged; not rerun as an aggregate in v0.27.

## Metrics and truth
90/90 Effective Java deep author drafts; five-book first pass mapped 157/504 units (31.2%), 347 still unmapped; 419 distinct mission drafts, 360 replacement overlays. No external reviewers enrolled or approvals received; 0 actual isolated Java worker executions; 0 production graders, XP/reward/mastery changes, or real learner mastery.

## Release gate
**BLOCKED.** Independent owner must enroll reviewers out of band, supply authorized private source access, independently pin review manifest hashes, perform technical and pedagogy reviews, and separately adjudicate signed evidence. The reviewer packet builder cannot self-certify approvals. No production Java sandbox. No CodeFit runtime change, merge, or deployment.
