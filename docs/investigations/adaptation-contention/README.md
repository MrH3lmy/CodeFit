# Adaptation reliability investigation (#295)

Baseline: main `9397de43d7862c0350791c8cc7cef3a5743d6243`. This packet separates two symptoms; it does not assume either is a production corruption or deadlock.

## Affected methods and original observations

- `AdaptiveQuestIntegrationTest.realRehearsalDefersRetentionAndAllHeldOutContentRemainsUnpresented`: real reference submissions are required to return PASSED before adaptation assertions. A prior overlapping Java/Electron run returned TIMED_OUT in `pass` instead. Original failure output did not identify the timed-out phase; that information cannot be reconstructed honestly.
- `AdaptiveQuestIntegrationTest.concurrentIdenticalResponsesCommitExactlyOneChoice`: two fixed-pool workers call `respond` with the identical command. Prior local runs received the documented pending/retry exception instead of two immediate Choice values. Isolated reruns and main CI also passed; neither result establishes deterministic success under all schedules.

A new pre-change, no-competing-workload sample reproduced bounded retry in **1/5** concurrent trials (542–853ms total); all five committed exactly one choice with zero exposure/evidence. Two original retention trials passed (9,736 / 7,721ms). Thus the concurrent outcome does not require CPU overload. Raw learner inputs, source, hidden checks, evaluation bodies and profile paths are not retained in this packet.

## Actual owners and budgets

`AdaptiveQuestService.respond` opens one JDBC connection per call, enables foreign keys and a 5,000ms SQLite busy timeout. It first checks immutable choice replay. Selecting commands call `derive`, which captures a coherent read snapshot, releases it, prepares estimates/policy data, then validates **same-connection PRAGMA data_version** under the short catalog writer lock before each publication. Own commits do not increment that connection's data_version; commits by other connections can invalidate the capture.

Both derivation and choice publication have three-attempt optimistic budgets. The documented contract in `docs/evolution/adaptive-quest-policy.md` explicitly permits pending/retry after exhaustion. The publication-time receipt check converges when reached, but a derivation can exhaust before that check even when another actor has committed the same command. Explicit replay with the same payload/ID then returns the saved receipt. Different-payload reuse remains rejected. No universal immediate-success guarantee is promised.

There is no adaptation background worker queue in this path: computation runs synchronously on the caller. The two-thread executor belongs to the concurrency test. `JavaExecutionCoordinator.tryStart` is a separate application-wide evaluation gate: it admits one operation or returns existing status; it does not enqueue work. Evaluation uses PREPARED → RUNNING → EVALUATED → FINALIZED, immutable artifacts, fenced ownership and atomic attempt/evidence finalization. No SQLite transaction spans learner execution; short child-registration checkpoints retain original cancellation/ownership callbacks.

`RunnerProfile.defaults()` freezes a 15s compilation budget, 128MiB memory, 32KiB output and three observed repetitions; check budgets come from the exact published rubric. `JavaCodeRunner` starts a monotonic phase deadline before process launch/registration and waits only the remaining time. CPU scheduling and spawn/registration consume wall time. A correct reference can therefore time out under resource contention; passing output is not guaranteed merely because the program is correct. Timeout/cancellation kill the process tree. `DurableEvaluationRepository` maps non-PASSED/FAILED/PARTIAL outcomes (including timeout) to UNAVAILABLE with inconclusive-execution limitations, not a negative capability claim.

`IsolatedDatabaseExtension`, per-test temporary databases and resource locks preserve global DatabaseConfig ownership. JUnit parallel execution is disabled; separately launched Electron/JVM workloads still compete for CPU and I/O. Private profiles and OS owner locks are independent; changing process scheduling is not the same as a SQLite lock conflict.

## Conclusions and scoped test correction

**Retry-required:** expected bounded optimistic concurrency, plus a test assumption that was too strong. A deterministic after-read hook commits another connection on all three attempts; the service must propagate pending. A competing identical choice is already durable; one explicit same-command replay must return it without another derivation. This proves the budget and replay contract without sleeps or favorable scheduling.

The original concurrency test now accepts only the two exact documented pending messages from an IllegalStateException; every SQL/eligibility/cancellation/other exception and Future timeout still fails. After both workers quiesce, **one** explicit original-command response must converge, match any immediate successful results, and replay identically. There must be exactly one choice and zero presentations, evaluation revisions, evidence observations and reward entitlements. This is not a blanket retry or suppressed concurrency failure: production still throws pending, and a deterministic test requires that error to propagate.

**TIMED_OUT:** phase-budget exhaustion is a valid evaluator outcome; the original trace alone cannot identify compilation vs execution or prove the scheduling cause. The strict real-reference PASSED assertion is unchanged. No time budget, evaluator/policy, production exception or orchestration semantics are modified. No production deadlock, invalid publication or resource leak has been demonstrated by the original observations.

## Bounded observability

The test-only probe runs five independent concurrency trials and two real retention trials, each on a fresh isolated database. It records request queue/run time and optional thread CPU time, phase durations/statuses/budgets, visible JVM/compiler counts, UTC observation times, cgroup quota/throttling counters and live child counts after runner return. A delegate-only CodeRunner wrapper preserves the exact source, limits, token and durable child callback. Phase output is capped at 128 records; it contains no source, stdin/stdout/stderr, artifact digest, check keys or evidence payload.

The phase budget at runner API entry is available. **Exact remaining budget when the OS first schedules the child is not measured**; no number is invented. The wrapper does not replace the cancellation token or bypass durable registration merely to instrument it. There is no executor queue to measure inside evaluation; response-test queue delay is measured at the real two-thread pool boundary. SQLite lock-wait time is not separately exposed: the 5s connection bound and the deterministic independent-writer experiment establish the guard behavior, not a fabricated lock-duration trace.

Full normal, CPU-affinity constrained and overlapping Electron/JVM experiments are recorded separately as they finish. A failure under stress must not be relabeled as a green verification. Final counts, commands, exact head CI and remaining limitations belong in the PR/issue evidence.

## Boundaries

Only test fixtures/assertions, bounded observation and this investigation change. Java remains authoritative. SQL stays author-only; no graph publication, new grader, mastery/reward policy, real-profile access or independent review status is introduced. Follow-up production changes would require evidence beyond expected bounded outcomes.
