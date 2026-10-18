# CodeFit Knowledge Compiler checkpoint v0.24 — 2026-10-18

Parent: **v0.23** verified private archive; no earlier checkpoint supersedes v0.23. Bounded increment: nonexecuting Java learner-work-order admission and fail-closed isolation gate.

## New artifacts

- `grader_boundary_v24.py`: accepts strictly sized/typed JSON submission work orders for three existing Build missions, rejects traversal, non-Java filenames, duplicate JSON keys, Unicode spoofing controls, oversize source, unknown missions, caller-supplied release flags; returns a fingerprint bound to the canonical source and immutable policy descriptor. **Never executes user-supplied code.** `execute_attempt` always refuses with `IsolationUnavailable`.
- `tests/test_grader_boundary_v24.py`: 49 author-only tests covering admission, canonicalization, fingerprint integrity, invalid payloads, deliberate sandbox-bypass attempts and the absolute refusal to run local Java.
- `output/deep_v24/capability_probe_v0.24.json`: Linux environment has JDK21 but no `nsjail`, `bwrap`, `firecracker`, `docker`, or `podman`; even an installed tool would not constitute independently verified isolation.
- `GRADER_BOUNDARY_IMPLEMENTATION_v0.24.md`, `PILOT_COMPLETION_CRITERIA_v0.24.md` and `RELEASE_GATES_v0.24.md`: explicit gates for external OS/VM worker, security red team, human adjudication, independent reviewers and explicit product authorization.

## Verified regression outcome

**380/380 Python tests**, **28/28 modules**, zero skipped modules, all individually executed as bounded processes. The original v0.23 suite covered 331 tests in 27 modules; this version adds 49 tests in one new module.

The first legacy comparative PDF module could not run because its historical source file was not mounted. After retrieving the historical reference PDF privately, that module was rerun successfully (9/9) and the final regression report was reconciled. Neither PDF is in the private ZIP, public handoff or GitHub.

## Stable verified counts (do not double count)

- 504 major five-book source units structurally discovered; 157 with at least some preliminary draft mapping, 347 unmapped.
- Effective Java 90/90 original deep author drafts (not independent approval).
- 419 distinct original mission blueprint slots (unchanged); 360 latest Effective Java overlay revisions replace drafts rather than add unique missions.
- Historical 1,150 author Java21 assertions and 242 expected negative `javac` cases (no new runtime assertion claims in this batch).
- v0.23 135 synthetic answer combinations (270 deterministic scoring operations) remain historical, no new learner assessment evidence.
- 28 technical review claims and six blind pedagogy packets pending. Independently approved claims/missions: **zero**.
- Java production graders, real held-out learner validation, verified mastery, product integrations: **zero**. The v0.24 admission module is deliberately **not** a code grader or a sandbox.

## Safety, copyright and release status

No publisher PDFs, source quotations or book example code are published or included in the checkpoint. Do not merge, alter live CodeFit learner runtimes, XP, rewards or mastery, or claim reviewed/pilot ready. Maintain private-source access separately and acquire independent technical/pedagogical/security assessments before any learner-code execution.
