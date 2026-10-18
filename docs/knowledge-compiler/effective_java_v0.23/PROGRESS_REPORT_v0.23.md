# CodeFit Knowledge Compiler — v0.23 progress

**Release status:** BLOCKED on independent review, secure grader and actual held-out learner assessment.

| Metric | v0.22 | v0.23 | Interpretation |
|---|---:|---:|---|
| Structurally discovered five-book units | 504 | 504 | Discovered, not reviewed |
| Units with some preliminary mission draft mapping | 157 | 157 | 31.2% draft mapping, not deep approval |
| Effective Java deep original author notes | 90 | 90 | First-pass authored 100% of this book |
| Latest deep EJ overlay replacements | 360 | 360 | Existing draft slots, not additive |
| Distinct original five-book draft mission slots | 419 | 419 | Not 419 approved challenges |
| Python tests passed | 284 | **331** | 27/27 separately run modules, 0 skips |
| Historical Java21 author assertions | 1,150 | 1,150 | No new Java assertions in v0.23 |
| Historical javac expected rejections | 242 | 242 | No new Java rejections in v0.23 |
| Enumerated synthetic closed-world answer combinations | 39 cases (mixed) | **135 combinations** | 270 deterministic scoring operations, not learner evaluation |
| Independently approved claims / missions | 0 | 0 | External reviewer not enrolled |
| Production graders / verified learner mastery | 0 | 0 | Secure JVM runner not built |

## What v0.23 has actually improved

An external assessor's *eventual* decision can now be checked for signature integrity, reviewed claim fingerprint, role, timestamp, and evidence content digest—provided a legitimately trusted owner has pinned the external reviewer roster. This is distinct from real signoff. The author generated temporary signing keys **only for tests**; none of these pretend to be real reviewers. Malformed, tampered, stale, duplicate, forged-role, wrong-claim inputs are rejected in test cases.

The three scoped Predict-stage synthetic scorers have been enumerated exhaustively across their documented options and checked against a separate arithmetic oracle. This is a stronger regression check but cannot measure educational efficacy, free-text reasoning, Java code correctness, learner mastery or independence.

## Next real blocker

Actual independent Java21 and pedagogy reviewers must assess the 28 technical claim requests and six blind mission prompts prepared in v0.22; the software cannot impersonate their decisions. A separate hardened execution service with negative abuse tests and human-adjudicated held-out outcomes is needed before programming/grading pilot. Four other books still need extensive source-to-mission work; 347/504 major units have no draft mapping.
