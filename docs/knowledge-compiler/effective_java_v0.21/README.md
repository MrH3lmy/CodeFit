# CodeFit Knowledge Compiler v0.21 — original-only agent review handoff

**UNMERGED / NOT LEARNER-RELEASE-ELIGIBLE**. Parent private checkpoint v0.20; new canonical checkpoint v0.21 is stored only in the existing private CodeFit Knowledge Compiler Google Drive folder. Do not use the GitHub handoff to reconstruct the full compiler or to publish source material.

## Scope

Internal adversarial author review of seven complete private Effective Java sections (Items 19–25) and 28 associated original mission drafts. One material Item 24 author-note correction, plus **six rubric replacements** (Items 21,22,23,24); these replace earlier draft IDs rather than add unique missions. The historical ledger remains 90 deep author notes, 360 overlays, 419 distinct five-book mission slots.

## Review discovery: Item 24

The previous draft claimed that all nonstatic member classes retain their enclosing object even when never used. Under the tested Java 21 `javac`, an unused synthetic outer-reference field can be **omitted**, while the constructor still requires an enclosing instance. A member accessing the owner does contain that field. Compiler class layout is distinct from language-level construction semantics; actual reachability must be tested, not inferred from an oversimplified slogan. Original author test fixture: `EffectiveJavaReviewV21.java`.

Additional clarifications: per-primitive locking does not guarantee compound batch atomicity; sealed interfaces and records do not auto-validate field values or guarantee deep immutability; enum types for stable concepts should not be confused with dynamic business thresholds.

## Evidence and restrictions

- Internal source-body fingerprints were rechecked privately for all 7 complete sections. No publisher passages, examples, book PDFs, or private Drive links are committed here.
- Java 21 author challenges: 33 positive assertions, 12 expected `javac` rejections. All historical + new Python regression modules: 255 tests in 24 separately invoked modules; zero skips.
- Independent technical/pedagogical approvals: **0**; production learner graders: **0**; verified player mastery: **0**.
- No merge, production grading, XP, rewards, mastery, learner-runtime changes, or deployment is permitted.

See `TECHNICAL_REVIEW_QUEUE_v0.21.md`, `RELEASE_GATES.md`, and review correction JSON files. Original Java and Python fixtures are research checks, **not** a secure hosted grader.
