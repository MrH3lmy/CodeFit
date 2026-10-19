# Review remediation evidence summary — original descriptions only

| Existing claim | New reproducible original demonstration | Unresolved boundary |
|---|---|---|
| EJ22-019-C3 | Documented inherited protected hook executes under a declared monitor, after committing state; propagation and constructor non-call tested | Designing a hook protocol for another API is still application-specific |
| EJ22-020-C4 | Interface-based capability with optional skeletal abstract helper and nonhelper implementer retaining unrelated superclass | General design principle, not a performance guarantee |
| EJ22-021-C3 | Purpose-built synchronized wrapper inherits split default operation: A-X-B; explicit synchronized whole operation: A-B-X | Does not assert concrete external JDK wrapper behavior |
| EJ22-022-C4 | Versioned immutable validated limits, stale CAS rejection, rollback as new revision | Author fintech design extension, not book source; local-only, not distributed policy/audit |
| EJ22-025-C4 | Two distinct package-private top-level classes compile; duplicate same-name types in separate sources fail javac | Convention and compiler restriction separated, no invented compile-order behavior |

The six existing pedagogical subjects remain intact by ID: four prompts revised for fairer assumptions/scoring and two unchanged; **no owner-only scoring criteria are public**. The private blind packet excludes author scoring and source text. All external approval statuses are PENDING (0 approved).

These are **author-side evidence improvements**, not independent ACCEPT decisions.
