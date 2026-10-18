# Independent reviewer handoff protocol — v0.22

This register is written by the original AI author and therefore cannot be self-certified.

### Java technical reviewer

Assign an independent person or separate auditor. Give them authorized access to the private original source through the owner, **not** a public or forwarded publisher PDF. For each claim in `claim_register_v0.22.json`, require independently reproduced reasoning or compiler checks, source-locator confirmation, a decision of `ACCEPT`, `REJECT` or `NEEDS_REVISION`, and reviewer identity. Published decisions must be original reasoning only, no copied publisher passage. On rejection, preserve the old reviewed claim lineage but replace the pending note rather than creating a new mission ID.

Prioritize Item 24's Java 21 synthetic-field caveat, Item 21's compound atomicity caveat, and Item 23's record invariant/deep immutability distinction.

### Pedagogy reviewer

Give **only** `blind_pedagogy_packet_v0.22.json`, not any author rubric or private synthetic calibration keys. Reviewer first produces a correct response, plausible wrong response, discriminator and learner fairness caveat. Then compare to the author rubric in a separate, logged adjudication phase. Do not equate stylistic disagreement with a technical mistake; document decisions and rationale.

### Grader/security auditor

`offline_closed_world_v22.py` is a pure structured-choice calibration prototype. It contains no actual sandbox, Java code execution, real learners, held-out independent answer keys or mastery logic. Treat it as **nonproduction**; secure code grading must be isolated in another security-reviewed execution boundary before any CodeFit runtime integration.

### Release constraints

No independent approvals are supplied by this handoff. Do not merge or alter CodeFit XP, rewards, mastery, or learner runtime without explicit user approval. Do not call these artifacts a production-ready knowledge compiler.
