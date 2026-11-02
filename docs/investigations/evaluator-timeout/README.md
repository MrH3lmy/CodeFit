# Evaluator deadline attribution (#295)

Baseline: merged main `375ca68cd4439c772d2ec1fa45ff3dd57048b028`. This continues #296's unresolved evaluator question; it does not change or repeat the optimistic-concurrency correction.

## Conclusion from a failed invocation

**API-level wall-clock resource-budget exhaustion was observed. No production evaluator correction is justified by this capture.** In the first direct Electron/startup overlap trial, the second reference submission in `AdaptiveQuestIntegrationTest.realRehearsalDefersRetentionAndAllHeldOutContentRemainsUnpresented` (`remaining-capacity@1`) exhausted its **shared compilation budget**, before any published check executed. This is a new attributed failure, not a retroactive attribution of #296's untraced failure.

The original strict PASSED assertion failed (scenario exit 1). The Java owner finalized TIMED_OUT as **UNAVAILABLE evidence**, released the execution gate, and returned the identical finalized result on replay without another compile/run. The artifact, policies, limits, cancellation token and durable ownership callback were unchanged.

### Failed phase 6: exact observations

[Complete failed trace](traces/electron-one-cpu-1.json); submission ordinal `submission-2` binds every phase event to that exact synthetic submission. UTC deadline observation: `2026-11-02T17:08:27.631694627Z`. No source, inputs, expected outputs, diagnostics, artifact hashes or learner identifiers are exported.

| Boundary                                 | Elapsed since original monotonic deadline origin | Deadline remaining | Child alive     |
| ---------------------------------------- | -----------------------------------------------: | -----------------: | --------------- |
| Deadline-start observation               |                                         0.053 ms |      14,999.947 ms | not yet spawned |
| ProcessBuilder.start returned            |                                       131.933 ms |      14,868.067 ms | yes             |
| First sampled nonzero child CPU          |                                       184.528 ms |      14,815.472 ms | yes             |
| Original registration callback returned  |                                       371.957 ms |      14,628.043 ms | yes             |
| Enter original wait                      |                                       392.470 ms |      14,607.530 ms | yes             |
| Original wait returned false             |                                    15,014.044 ms |         −14.044 ms | **yes**         |
| First termination request                |                                    15,014.198 ms |          exhausted | yes             |
| Second best-effort termination (finally) |                                    15,614.660 ms |          exhausted | yes             |
| Child exit observed                      |                                    15,647.758 ms |          exhausted | **no**          |
| Compile returned TIMED_OUT               |                                    16,045.978 ms |          exhausted | **no**          |

The JDK reported the compiler alive when the original wait returned false. This is not an independent kernel-liveness observation: process-exit notification/reaper scheduling can lag OS exit. Consequently the capture cannot rule out delayed completion notification or establish exactly when native compilation finished. Almost the entire 15s budget was available after registration; a long SQLite registration stall did not consume it. The runner waited the remaining budget, requested termination and observed JDK completion/cleanup. Termination/stream cleanup can extend caller latency beyond the execution budget; this is not a real-time sandbox guarantee.

`COMPILE` is a shared pre-check phase, not a new rubric check. No `concurrent-observation` or `functional` check ran for submission 2. The separate first submission had passed its actual published checks. Successful later trials cannot stand in for this failed trace.

The compiler made observable CPU progress (10ms CPU at the first positive sample), but its exact first OS scheduling instant, full CPU allocation and per-process runnable-queue delay are **not measured**. The trial pins all participating process trees to CPU 0, concurrently runs actual packaged Electron analytics and the existing native-startup diagnostic with two load JVMs. The enclosing cgroup still permits two CPUs (`200000/100000`); affinity is the stronger restriction. Cgroup throttle counters increased only slightly in the failed phase (four periods, 28,590µs); affinity contention need not appear as cgroup quota throttling. Do not call that counter a measurement of all starvation or infer a javac internal deadlock from missing scheduling data.

## Instrumentation and observer limits

`src/diagnostics/java` is compiled only by the explicit preparation script. A standalone diagnostic-only POM (`scripts/diagnostics/pom.xml`) resolves pinned **ASM/ASM Tree 9.8**, test scope only, to insert memory-only callbacks into the test JVM's loaded copies of:

- `JavaCodeRunner`: phase request, original nanoTime deadline, successful spawn, successful original attach, wait entry/result and return.
- `RunCancellationToken.killTree`: termination requests, including cancellation/finally.
- `JavaChallengeEvaluator`: actual published check ID immediately before the original CodeRunner.run call.

There are no production source changes, replacement evaluators, modified tokens, changed method arguments, altered branches or new deadline calculations in the runner. The transformer computes stack maxima, preserves existing frames/control flow and requires 12 runner / 1 cancellation / 1 evaluator anchors. The scenario forces all three classes to load and fails closed before execution if transformation fails. Missing check locals, signature drift or mismatched anchors invalidate the capture rather than silently producing phase guesses.

A separate daemon samples only the tracked child's total CPU duration every 100ms. `FIRST_OBSERVED_CHILD_CPU` is a sampling upper bound, **not** kernel first execution. `CHILD_EXIT_OBSERVED` is a Process.onExit notification, **not** a precise kernel exit timestamp. Spawn means the Java API returned; child execution can begin before that return. No OS event is invented. `childCpuNanos` is a measurement only on `FIRST_OBSERVED_CHILD_CPU`; zero on other events is an unmeasured placeholder, not evidence of zero CPU use. Likewise, zero remaining time before `DEADLINE_STARTED` means no deadline measurement exists yet. The sampling period is nominal; scheduling may delay a sample. A bounded outside-evaluation wait verifies exit callbacks; it does not extend execution budgets.

Events stay in memory (maximum 512), then a strict JSON writer exports scalar identities, times, booleans and counters after the scenario. Sensitive objects/exception messages are never serialized. The verifier rejects unexpected fields, mismatched identities, missing lifecycle anchors, non-exhausted claimed deadlines, truncation and observer errors. Five adversarial validator tests cover those boundaries. A synthetic infinite-loop timeout and cancellation exercise the real instrumented runner in ordinary CI; deliberate overload does **not** run in correctness CI.

Instrumentation is not zero overhead. The failed trial measured **158.143ms synchronous observer wall time over all six phases**, including initialization/reflection/descheduling inside hooks. Pre-deadline environment reads and asynchronous sampling add further experimental perturbation; no claim is made that the same scheduler history would occur uninstrumented. This overhead could tip a near-boundary request. The observed original wait returned false after its real deadline; its boolean result and JDK liveness state were captured directly. This establishes API-level budget exhaustion, not kernel-level execution duration. The finding is correct API-level deadline enforcement in the observed instrumented invocation, not proof of one precise OS starvation mechanism, absence of delayed exit notification, or universal correctness under every race.

Capture producer SHA-256: `95f92c1745bd54a8f3203c6933d869d2dc42c8baf54e3875b47b5e430d4df54c`. Captures preceded final tooling hardening (null-name transformer guard, non-overwriting output creation, cross-thread cancellation correlation, standalone dependency isolation and formatting); phase hooks/event schema are unchanged. The final tooling's fresh synthetic timeout/cancellation regression also passed. The baseline product bytecode and published challenge assets were unchanged throughout.

## Fixed controlled sample

All trials execute the same affected method with its original fixed clocks and packaged reference assets, on fresh disposable SQLite databases. Only command/presentation IDs and temporary paths are newly allocated; none are exported. There is no automatic retry, replacement submission or loop until success.

| Trial              | Process-tree affinity        | Workload                                     |             Scenario exit |
| ------------------ | ---------------------------- | -------------------------------------------- | ------------------------: |
| quiet-1            | allowed 0–2, cgroup two CPUs | no competing workload                        |                         0 |
| one-cpu-1          | CPU 0                        | no competing workload                        |                         0 |
| two-cpu-1          | CPUs 0,1                     | no competing workload                        |                         0 |
| startup-two-cpu    | CPUs 0,1                     | existing startup diagnostic, two load JVMs   |                         0 |
| electron-one-cpu-1 | CPU 0                        | packaged analytics case + startup diagnostic | **1 (compile TIMED_OUT)** |
| electron-one-cpu-2 | CPU 0                        | same bounded overlap repeated once           |                         0 |

[results.json](results.json) retains every phase's actual outcome/budget/duration, exit code, observer time and full-trace digest for all six trials. All six complete bounded traces are retained under `traces/`. The corresponding stressed startup samples were **3/3**, **2/3**, **1/3** passed; both one-case Electron stress runs failed. These are preserved stress failures, not counted as green correctness verification.

The published performance claim is deliberately limited: quiet reference submissions pass; the same finite wall budgets can legitimately expire under severe contention. A correct solution is not guaranteed a PASSED result under arbitrary resource starvation. A timeout is an unavailable/inconclusive capability observation, not failure evidence or automatic loss of mastery. No budget increase or new production retry is warranted.

## Reproduce and validate

Use a Java 21 JDK, Maven, Python 3 and Linux. Optional tooling requires its own full JDK; it is not part of the stripped packaged runtime. The application POM and its strict dependency guard remain unchanged. From repository root:

```sh
export JAVA_HOME=/path/to/jdk-21
sh scripts/diagnostics/prepare-timeout-trace.sh
java -javaagent:target/timeout-trace-agent.jar \
  -cp "$(cat target/timeout-trace-runtime.txt)" \
  com.codefit.evolution.adaptation.TimeoutScenario runner-regression target/new-regression.json
python3 scripts/diagnostics/verify_timeout_trace.py target/new-regression.json
python3 -m unittest discover -s scripts/diagnostics -p 'test_*.py'

DISPLAY=:99 taskset -c 0 java -javaagent:target/timeout-trace-agent.jar \
  -cp "$(cat target/timeout-trace-runtime.txt)" \
  com.codefit.evolution.adaptation.TimeoutScenario retention target/new-retention.json
```

Output files must not already exist; final tooling refuses overwrite/symlink targets. The scenario owns a fresh temporary directory, calls the existing fixture's explicit `DatabaseConfig.useDatabaseFile` before initialization and deletes that directory after capture. It cannot select a real learner profile. No IsolatedDatabaseExtension behavior is changed.

For native overlap, concurrently run the existing `desktop/scripts/offline-linux.sh` wrapper with `npm run test:e2e -- --grep 'five real charts' --trace off --output <unique-directory>` and `CODEFIT_STARTUP_LOAD=2 playwright test --config playwright.startup-diagnostic.config.ts --output <another-unique-directory>`. Supply the prepared packaged executable and Java21 JAVA_HOME using existing conventions. Apply the same `taskset` mask to each process tree. Use separate Playwright outputs, no retries and a fixed sample count. Preparation/packaging precedes trials, not inside quiet measurements. The collection script used a 240s outer diagnostic watchdog; none fired.

## Verification, security and disposition

Ordinary full Java, Node, sandboxed packaged Electron, startup, Linux packaging and production audit results plus exact-head GitHub CI are recorded in the associated draft PR completion ledger. They remain separate from the failed stress trials above. Runtime binaries contain neither agent classes nor ASM diagnostic dependencies; the standalone tooling dependency is opt-in and test-scoped. No renderer or IPC command can activate the agent.

No evaluator/mastery/evidence policy, profile ownership, real learner database, SQL curriculum release gate, immutable submission or reward behavior changes. Tests check actual finalization, UNAVAILABLE timeout evidence, released execution gate and identical finalized replay. Existing full regressions cover cancellation, correction, recovery and duplicate prevention. No debugger pauses, sleeps, test skips, blanket retries or timeout inflation were used.

Recommended disposition: review the observability tooling and evidence independently; no production evaluator fix is supported. Keep normal correctness jobs separate from deliberate saturated native workloads on small CPU quotas. If resource admission or adaptive time budgets are desired, they require separate product/policy review. The historical untraced failures remain un-attributed individually; this captured equivalent failure establishes compilation-budget exhaustion for the scenario without inventing their missing data.
