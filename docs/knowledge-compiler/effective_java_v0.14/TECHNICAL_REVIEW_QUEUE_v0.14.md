# Independent technical and instructional review queue — CodeFit v0.14

| Item | Source-informed claim to review | Must-fail counterexample | Release precondition |
| --- | --- | --- | --- |
| 70 | Checked exceptions signal actionable recovery; runtime contracts signal programming errors | Catching `Throwable` and returning success; retrying `Error` | Correct recoverability classification, meaningful documented caller action |
| 71 | Avoid ceremonial checked exceptions; keep normal absence distinct from transport failure | Treating network outage as `Optional.empty()`; TOCTOU test-then-act | Documented result/failure semantics and source compatibility |
| 72 | Standard exceptions encode well-known Java contract failures | `IllegalStateException` for invalid index; `ConcurrentModificationException` for database conflict | Correct precise exception families and boundaries |
| 73 | Translating lower-layer exceptions should preserve cause and match the abstraction | Broad catch-and-wrap of programming errors; lost cause | Correct public type, traceable root cause, no sensitive data in message |
| 74 | Public Javadoc specifies relevant checked and unchecked failures accurately | Clean doclint with materially false behavior text; missing checked declaration | Generated HTML and contract tests plus independent semantic review |

For **every mission**: identify human reviewer, version, decision and rationale; require one accepted response and two realistically wrong responses with validated outcomes, fairness controls, sandbox resource limits and feedback policy. This file assigns work; it is **not** approval.
