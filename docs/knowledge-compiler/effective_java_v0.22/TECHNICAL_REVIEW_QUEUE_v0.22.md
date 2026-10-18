# v0.22 independent review queue — awaiting external reviewers

The 28 claim requests in `output/deep_v22/claim_register_v0.22.json` require claim-level accept, reject, or needs-revision decisions. All are **PENDING**, including claims confirmed by author's Java 21 regression tests. Source inspection must use the preferred private PDF and avoid reproducing copyrighted passages into public GitHub.

| Item | Reviewer challenge priority |
|---|---|
| 24 | **P0:** verify unused synthetic enclosing-reference optimization on Java21 javac, explain compiler-specific nature and constructor/lifetime distinction |
| 21 | **P1:** distinguish per-primitive locking from batch atomicity without asserting an unobserved race |
| 23 | **P1:** check sealed hierarchy field-invariant validation and shallow record mutability |
| 19 | P2: superclass constructor override behavior and self-use contract |
| 20 | P2: interface default restrictions and conflicting defaults |
| 22 | P2: constant-interface API visibility and stable enum-vs-changing policy distinction |
| 25 | P2: legal multiple package-private declarations versus public filename and duplicate type constraints |

Six blind pedagogy missions: EJ20-021-Q1, EJ20-022-Q3, EJ20-023-Q1, EJ20-023-Q3, EJ20-024-Q1, EJ20-024-Q3. Do not send author rubric or calibration keys to the blind reviewer.

**Independence rule:** this AI author is not its own independent reviewer. Reviewers must identify themselves, document complete source inspection or independently grounded behavior, and give reproducer evidence. A failed claim supersedes the old author draft rather than creating a new distinct mission.
