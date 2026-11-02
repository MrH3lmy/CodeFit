# Library startup reliability (#291)

Baseline: main `5eaf823b303081cfc9725ca3b4383588421b3434`. This work is independent of the Analytics #285 branch. Production Java, Electron, renderer, IPC and SQLite behavior are unchanged.

## Reproduction and root-cause scope

The unchanged sandboxed packaged suite was run on Linux/Xvfb while the full Java suite was active. On this runner the cgroup CPU quota was 200000/100000 (two CPU equivalents). It completed with 86 passes and six timing failures. Three original cold-library checks expired at their five-second text assertion while the status remained `Working with the local Java engine…`: initial read before crash recovery, initial read before renderer-crash cleanup, and the common `library()` helper in the Unicode-deck case. The exact deck/restart scenario originally reported in #291 passed this run. Other stressed workflow failures are not claimed fixed here.

A separate bounded, test-only probe measured genuine main-frame UI requests on a clean-main packaged artifact. It records only milliseconds and stage labels: no frame bodies, profile identities, paths, SQL, credentials, source or deck content. Maximum sixteen events and 64 KiB transient handshake buffer; nothing is added to production logs or bridge permissions.

The control adds two *real* trusted `DesktopApplicationMain` profile initializations concurrently with the target startup. Each has a separate private disposable profile, drains its pipes, receives EOF immediately and exits after initialization. This represents competing native fixture startup, not an artificial sleep or delayed response. No executable arguments come from a renderer. The normal Java session, authorization, READY validation and transport deadlines remain in force.

| Loaded sample | Spawn → READY | UI assertion wait | READY → state response | Pending receipt lookup | Cleanup (all three children) |
|---:|---:|---:|---:|---:|---:|
| 0 | 7,379 ms | 7,854 ms | 118 ms | 5 ms | 507 ms |
| 1 | 14,069 ms | 14,521 ms | 180 ms | 1 ms | 304 ms |
| 2 | 5,725 ms | 6,495 ms | 203 ms | 1 ms | 250 ms |

All three returned the correct eight-deck state and preserved the synthetic real-profile sentinel. All target and load children were verified absent/zombie after close, so their OS-owned locks were released. Stage times are relative to probe installation; **spawn-to-READY deltas**, not absolute event timestamps, are compared to the fifteen-second native startup deadline.

This confirms a test readiness-deadline mismatch for successfully supervised startup under contention. It does **not** establish the precise JVM/class-loading/SQLite/fsync share of initialization, or prove that every historical timeout had this cause. No persistent hang, authorization failure or leaked owner was observed in these samples. Internal lock acquisition was not separately instrumented; no such timestamp is invented.

## Narrow correction

`expectNativeLibrary` waits for the same expected deck-description text using the existing fifteen-second `JavaSession` startup bound. It is used only at ten cold-start/replacement points in `desktop.spec.ts`: first connection, cancellation/reconnect, crash/retry, renderer/app restart, hard-crash/second-instance setup and `library()` entry. The first test retains its stronger full text assertion.

The three warm library refresh/second-instance assertions keep five seconds. Deck-write, error, recovery, child-termination, authorization and all other ordinary assertions are unchanged. No global Playwright timeout, automatic retry, production deadline, security flag or fixture-isolation policy changes. A genuinely failed/timed-out Java start still fails the test; the helper does not manufacture readiness.

The controlled diagnostic uses this same helper and a separate opt-in Playwright config. It is outside the default E2E test directory. No diagnostic code, extra process launch or tracing is shipped in the application.

## Reproduce safely

Use an unsigned Linux package built from this branch, a live Xvfb display, Java 21 and the existing regular-user offline namespace script:

```
cd desktop
CODEFIT_STARTUP_LOAD=2 CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" sh scripts/offline-linux.sh ./node_modules/.bin/playwright test --config playwright.startup-diagnostic.config.ts
```

Set `CODEFIT_STARTUP_LOAD=0` for a no-extra-JVM control. The count is capped at two. The probe creates private temporary profiles, sets invalid inherited JVM-injection inputs to exercise the existing sanitation, and checks its real-profile sentinel. It closes Electron and verifies all owned children terminate. No real learner profile is opened.

Separate full regression verification uses standard Java/Node/packaged commands and final-head GitHub CI. Final counts and control measurements are recorded in the accompanying PR. The full stressed baseline's unrelated timing failures and Java evaluator timeout must not be represented as a green stress suite or suppressed by broad deadline changes.

## Residual limits

Native initialization latency remains load-sensitive; this patch changes test expectations at the documented startup boundary, not startup performance. Overload can still exceed production budgets and must remain an honest failure. No universal hang/process-leak fix or Windows/macOS guarantee is claimed. Xvfb liveness must be checked rather than trusting a stale socket. SQL remains author-only and all real-profile/assessment/reward/evidence contracts remain unchanged.

## Reduced-load control and result provenance

After the full Java and baseline packaged workers stopped, the no-extra-JVM samples waited 1,873 / 1,885 / 1,991 ms at the UI assertion, with cleanup in 83 / 63 / 61 ms. Their spawn-to-READY deltas were 1,448 / 1,421 / 1,603 ms. `timings.json` retains only redacted stage records from the measured runs, including a three-pass run using the fixed shared helper.

To recreate the high-contention condition rather than just the bounded overlap control, run the isolated Java suite in another terminal on a two-CPU-equivalent runner while the clean-main packaged suite/probe starts. Record actual resource limits and do not infer a performance guarantee from a single run. The CI probe intentionally runs in a separate step after ordinary regressions; it validates three real native initializations and cleanup without making CI depend on a whole concurrent Maven suite.

A Java adaptation integration scenario returned TIMED_OUT under this deliberate combined load. Its unchanged isolated rerun passed, and final-head #285 Java CI passed all 2,023 tests. Evaluator deadlines/expectations are not changed here. This is evidence that workload contention can exceed other budgets too, not justification for expanding this library-startup correction.
