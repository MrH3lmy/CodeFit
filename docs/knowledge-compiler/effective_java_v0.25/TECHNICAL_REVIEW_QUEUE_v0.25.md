# CodeFit v0.25 — External review and security queue

**Technical content review (external):** 28 claim-level decisions from v0.22, including Java 21 nested-class reference behavior (Item 24). Obtain individually signed independent reviewer evidence rather than reusing author self-tests.

**Pedagogy review (external):** six blinded prompts from v0.22; independently assess ambiguity, alternate correct approaches, misconceptions and fairness. Keep answer keys separate.

**Worker security (external):** verify actual sandbox OS/VM isolation, terminate process groups, bound CPU/memory/PIDs/I/O, disable egress, verify secret and hidden-test separation, and audit key provenance and replay DB behavior. Tests in v0.25 are *contract-level only*, not an external attack evaluation. No production runner exists.

**Real learner validation:** prepare consented held-out examples authored independently and test false positives, false negatives, alternative correct solutions and error messages. No learner execution before the worker is approved.

**Release owner:** separately authorize any CodeFit changes. Do not merge experimental branches or change XP, mastery, reward, or production progression state as part of compiler preparation.
