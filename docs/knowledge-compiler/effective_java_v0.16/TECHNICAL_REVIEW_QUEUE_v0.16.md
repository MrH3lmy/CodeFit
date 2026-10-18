# CodeFit independent technical and instructional review queue — v0.16

| Item | Claim for independent review | Counterexample to falsify | Release-specific requirement |
| --- | --- | --- | --- |
| 80 | Executors separate work from policy but do not automatically bound load | Fixed pool with unbounded queue; virtual threads without downstream cap | Verified admission, rejection, cancellation, shutdown and provider-capacity policy |
| 81 | Per-key concurrent-map operations and coordination primitives outperform fragile manual protocols | get-then-put race; latch starvation; `if`-guarded `wait` | Worker failure/finally handling; timeouts; spurious wakeup response |
| 82 | Concurrent use is an explicit contract, not a `synchronized` keyword | synchronizedMap view traversal without wrapper lock | Accurate conditional lock ownership, snapshot and extension guarantees |
| 83 | Lazy optimization requires measured need and safe publication | Nonvolatile double check, null sentinel, duplicated initialization | Correct JMM behavior, defined failure retry, benchmark evidence |
| 84 | Progress must not depend on scheduler hints | Busy spin, pool self-starvation, `Thread.yield` as correctness fix | Bounded queues, cancellation, starvation/liveness and CPU measurement |

Require a named independent reviewer, verified good submission, two plausible wrong submissions, observed outcomes, sandbox limits and versioned approval per mission. This queue is **not** review approval.
