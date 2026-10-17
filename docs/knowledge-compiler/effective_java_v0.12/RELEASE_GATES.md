# v0.12 release gates — NOT CLEARED

- [ ] Independent claim-by-claim technical audit of all 46 draft notes, including currency rounding and overflow, wrapper caches/null semantics, typed capability authorization, Java21 concat, ordered interface contracts.
- [ ] Independent pedagogical review of 20 new mission briefs, rubrics, negative controls and fairness.
- [ ] Separate held-out learner examples and safe sandbox grading with resource limits, adversarial tests and feedback; no live graders exist.
- [ ] Behavioral compatibility and security checks: financial precision, multitenancy boundaries, ThreadLocal cleanup, stateful mutation, ordering guarantees, JDK version deltas.
- [ ] Copyright/private PDF and data audit before any external learner release.
- [ ] Explicit authorization for production integration; no XP, mastery, rewards or merge now.

All 122 Python test methods passed in six bounded groups, including 9 historical v4 PDF comparison methods; single-process suite has not been independently run to completion.
