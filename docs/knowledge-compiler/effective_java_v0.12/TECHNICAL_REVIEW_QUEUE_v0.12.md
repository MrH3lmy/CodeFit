# CodeFit independent technical review queue — new original v0.12 drafts

Author verification is not independent approval. Record **human/independent reviewer ID, date, claim, version, decision, and reasoning** separately; never self-approve or change learner_release based solely on compiler checks.

| Item | Claim to verify | Required counterexample | API/security boundary |
| --- | --- | --- | --- |
| 60 | Decimal scale versus numeric equality; explicit rounding; checked minor-unit arithmetic | Nonterminating division, currency mismatch, long overflow | Currency legal scale, major/minor conversions |
| 61 | Wrapper identity versus value, null unboxing, overflow-safe comparator | Cache-dependent equality; comparator subtract overflow | Null import contract and primitive-specialization performance |
| 62 | Typed values and aggregates rather than concatenated strings | Delimiter collisions and untrusted token reuse | Authorization still separate from typed identity; ThreadLocal cleanup |
| 63 | Large repeated concat versus builder and streaming | Escaped CSV, giant output preallocation overflow, mutable global builder | Representative Java21 benchmark, bounded memory |
| 64 | Interface substitution without behavioral drift | HashSet drops ordered traversal, Map.of rejects null and mutation | Java21 SequencedSet/SequencedMap behavior |

For every learning mission, obtain one human-reviewed good submission and at least two plausible wrong submissions with verified outputs, rubric acceptance boundaries, false-positive analysis and safe resource limits. No grader or mastery emission is currently implemented.
