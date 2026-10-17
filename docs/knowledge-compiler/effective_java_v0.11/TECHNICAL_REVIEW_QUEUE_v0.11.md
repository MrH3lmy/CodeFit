# Independent review queue — original CodeFit candidate assets v0.11

Author validation is not independent approval. Use a reviewer other than the drafting agent; record reviewer, version, decision and rationale per note and mission before changing `learner_release`.

| Item | Technical proof obligation | Semantic confusion to falsify | Java 21 verification |
| --- | --- | --- | --- |
| 55 | Ordinary absence vs null contract and exceptional failure | `orElse` eager work mistaken for lazy; wrapped empty collections | `orElseGet`, no-arg `orElseThrow`, primitive optionals |
| 56 | Public contract includes params, throws, return and ownership | doclint clean interpreted as behavior verified | HTML5 generated Javadoc and doclint output |
| 57 | Loop-local scope and definite assignment prevent stale references | hoisting cursor across independent passes | `var`, flow-scoped pattern variables |
| 58 | Nested-product traversal vs zip and structural mutation rules | enhanced-for assumed to permit `List.remove` in-loop | `Iterable`, `removeIf`, sequential collection APIs |
| 59 | Bounded random API and cryptographic role separation | ThreadLocalRandom used for secrets; modulo biased | `RandomGenerator`, `SecureRandom`, `InputStream.transferTo` |

Evidence to attach independently for each mission: one correct solution and two plausible wrong submissions, expected observable outputs/failures, rubric boundary examples, counterexample analysis, sandbox resource limits and feedback semantics. No reward or mastery signals should be emitted by experiments.
