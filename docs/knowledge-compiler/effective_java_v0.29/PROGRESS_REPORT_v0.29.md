# CodeFit Knowledge Compiler - v0.29 Progress

## New progress
- Implemented read-only, private `review_docket_v29.py` reconciling 28 technical + 6 pedagogy review subjects with the pinned v0.28 SQLite ledger.
- Added redacted `public_progress(...)` and external action queue across 34 pending review subjects. No reviewer identities, signed decisions or private answer keys are included in the public handoff.
- Added 29 author-side adversarial tests; all **566/566 Python tests, 33 modules, 0 failures, 0 skips** pass.
- Reread the release gates and explicitly documented that the v0.28 ledger stores signature digests only; original signed envelopes must remain in a separately secured evidence vault for later signature revalidation.

## Stable corpus metrics
- First-pass Effective Java source extraction: 90/90.
- Deep original author drafts: 90/90 (NOT independently approved).
- Distinct five-book blueprint mission IDs: 419; replacement overlays: 360.
- Five-book structure units with some draft mapping: 157/504 (31.2% preliminary mapping, not completed semantics).
- Historic author Java21 assertions: 1,150; historic expected javac rejections: 242 (not rerun as new counts).

## Release gates still at zero
- External independent reviewer enrollments: 0.
- Actual external signed review decisions received: 0.
- Independent technical claims approved: 0/28; independent pedagogy reviews approved: 0/6.
- Owner adjudications: 0; audited isolated Java workers: 0; production Java graders: 0; real learner mastery confirmed: 0.

## Recommended next milestone
Owner-led reviewer enrollment and signed human evidence through issue #253. In parallel, design a separately anchored raw-signature evidence vault and independent Java worker audit. Do not promote author tests to content approval, ship learner grading, merge branches or change XP/mastery/reward runtime.
