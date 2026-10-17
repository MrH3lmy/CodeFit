# Independent review queue — CodeFit v0.13

| Item | Technical claim needing independent review | Falsifying control or edge case | Release boundary |
| --- | --- | --- | --- |
| 65 | Runtime `asSubclass` type validation; public constructor and JPMS access | Incorrect provider, private constructor and exception cause | Allowlist and interface-driven dispatch, no unrestricted reflective loader |
| 66 | Native memory/ownership and Java21 FFM preview distinctions | Off-heap leak, use-after-release, hot boundary calls | No real native code executed by author fixture; platform/security approval needed |
| 67 | Profile/algorithm choice before micro-optimization | Speedup on leaf method with worse p99 and API break | Reproducible load tests and rollback before use |
| 68 | Naming conventions versus binary/serialization compatibility | Record accessor incorrectly treated as Bean getter | Review reflective/framework contracts per deployment |
| 69 | Ordinary absence and test/act concurrency races | `isEmpty()+remove()` race and swallowed `NoSuchElementException` | Atomic polling and exception propagation in concurrent workflows |

For each mission require a reviewer record, one accepted answer, two plausible failed answers, observable outputs, clear rubric thresholds, a sandbox policy, and explicit non-mastering/non-rewarding behavior. This queue does **not** constitute review approval.
