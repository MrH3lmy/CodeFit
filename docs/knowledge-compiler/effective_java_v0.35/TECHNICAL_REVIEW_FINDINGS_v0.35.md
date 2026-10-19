# Original author-assisted technical findings — not external review decisions

All 28 claim IDs under the pre-existing Effective Java Items19–25 docket were screened for alignment with original privately held source sections, existing reproducible JDK21 fixtures, and design caveats. **No reviewer ACCEPT/REJECT decision was created**, and none of these claims is externally approved.

**Five claims requiring stronger direct demonstration before external reviewer adjudication:**
1. `EJ22-019-C3`: document protected override hook self-use order, errors, concurrency assumptions in an original API contract example.
2. `EJ22-020-C4`: supply runnable optional skeletal-implementation helper and a different implementation lineage to prove the interface advantage; current general multi-interface fixture does not test this directly.
3. `EJ22-021-C3`: supply a real original synchronized wrapper whose inherited default operation violates whole-operation locking, without treating a 2018 third-party implementation as current.
4. `EJ22-022-C4`: add a versioned validated runtime policy with rollback/refresh tests. This is **domain design advice**, not a verbatim book claim.
5. `EJ22-025-C4`: show compilation-order/duplicate-type maintenance risk separately from legality of multiple distinct top-level package-private classes.

**Provenance labels required:** `EJ22-019-C4`, `EJ22-023-C2/C3/C4`, `EJ22-024-C2/C3` contain post-2018 Java features or toolchain-dependent observations, which must be identified as modern Java extensions. Source hashes matched, but old TOC/page slicing can include headers or next-chapter text. Change locators only with new versioned evidence and explicit review requirements.

**New original deterministic Java21 evidence:** `EJ22-021-C1/C2` gain a concrete synchronized-primitives/nonatomic-batch witness. Under a controlled schedule, operations occur `A, X, B`, not an uninterrupted `A, B` batch. This does not by itself resolve wrapper-specific `C3`.

All unflagged claims are **preliminarily consistent with available author-side evidence**, not independently endorsed. Real reviewer and owner adjudication remain mandatory.
