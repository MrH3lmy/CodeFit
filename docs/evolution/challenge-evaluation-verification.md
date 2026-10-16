# EV-06 verification and review

For [#214](https://github.com/MrH3lmy/CodeFit/issues/214). See
[implemented contracts and limits](challenge-evaluation.md). Checks use original installed #213
assets, isolated SQLite files, the actual Java 21 runner, and bounded failure fixtures. No real
learner database is read or changed.

## Pack validation matrix

`JavaCodeRunnerCompilationTest` also verifies actual compiler-process cancellation/cleanup, typed
missing-JDK failure, class-name path validation, UTF-8 filenames, and registration time included in
the deadline.

`JavaChallengeEvaluatorTest` runs every authored reference and every declared MUTANT asset, plus the
atomic primitive alternative, an explicit constant-output counter and a transfer-dropping repair.

| Challenge | Cohort | Correct mechanism | Incorrect fixture / expected protection |
| --- | --- | --- | --- |
| baseline-trace | BASELINE | Exact keyed diagnosis | Wrong key fails; reflection remains ungraded |
| baseline-localize | BASELINE | Exact keyed diagnosis, same diagnostic family | Wrong key fails; related exposure retained |
| baseline-counter | BASELINE | Reviewed monitor-protected Map merge/reduction | Unsynchronized compound updates cannot satisfy required mechanism |
| counter-repair | PRACTICE | General intrinsic monitor or one AtomicLong primitive | Lost update, volatile compound update, split atomic get/write, replaceable atomic arrays and constant output never complete |
| counter-repair-b | PRACTICE | Same supported mechanisms, related metric-counter variant | Both authored mutants blocked; never independent-family evidence |
| atomic-boundary-drill | PRACTICE | Whole-operation intrinsic monitor | Broken enter/leave boundary cannot complete |
| remaining-capacity | PRACTICE | Whole-operation intrinsic monitor | Nonconserving/repeated reservation mutant blocked |
| distinct-id-accumulator | PRACTICE | Whole-operation intrinsic monitor | Duplicate accumulation mutant blocked |
| regression-localize | PRACTICE | Reviewed stateless cursor filter | Wrong pagination boundary cannot complete |
| lock-order-repair | PRACTICE | Reviewed fixed two-lock min/max ordering; general single monitor also supported | Unsafe opposite lock order has UNSUPPORTED required proof even if finite runs happen to finish; timeouts remain unavailable, not proven deadlock |
| inventory-transfer | TRANSFER | Whole-operation intrinsic monitor | Broken stock/held/tombstone behavior blocked |
| queue-retention | RETENTION | Whole-operation intrinsic monitor | Invalid acknowledgement/state accounting blocked |
| post-ledger | PILOT_POST | Reviewed monitor + immutable Entry mechanism | Wrong revision/replacement behavior blocked |
| post-expiry | PILOT_POST | Reviewed monitor + pure expiry predicate | Invalid logical-time/reuse behavior blocked |

All **14** authored references and the atomic alternative pass. All **17** authored mutant assets are
prevented from PASSED. These assertions test bounded rubric behavior; they are not a claim that every
mutant will fail a stochastic workload on every schedule. Specifically, the volatile compound update
fails its structural check regardless of stress outcome. The lock-cycle mutant cannot gain a verified
liveness claim from passing sequential/stress runs. The transfer-dropping and constant-output fixtures
fail their actual required functional checks, rather than relying solely on unsupported syntax.

The five AST templates intentionally have narrow admission. Semantically changed callbacks, custom
multi-lock implementations and broad CAS/ReentrantLock protocols can be correct and still UNSUPPORTED.
This is explicit scope, never an automatic learner failure or a silently skipped required check.

## Acceptance and adversarial coverage

| Requirement | Test / evidence |
| --- | --- |
| Real local submissions; exact frozen bytes and harness | `JavaChallengeEvaluatorTest`, `ChallengeEvaluationIntegrationTest.realSubmissionFreezesDraftAndAtomicallyProducesOneLocalAttemptAndObservation` |
| Trusted harness cannot be replaced or spoofed | Extra/modified Main, System output/exit, unknown source forms, shadowing-sensitive resolved-symbol admission; no annotations/extra types/callbacks accepted outside reviewed templates |
| Complete result vocabulary / no false capability failures | Compile-error, runner-unavailable, timeout, cancellation, OOM, runtime error, process-start failure, truncated-output and bounded-nontermination fixtures |
| Required checks / actual guarantees | Full pack matrix, atomic alternative, volatile structural failure, split get/write rejection, dropped transfer and constant-output functional failures |
| Same command / payload conflict / lost response | Existing result equality and row counts, conflicting command rejection, every explicit fault boundary |
| Distinct deliberate attempts / family correlation | Same-artifact repeat, related source identity, concurrent different commands, durable prior family counts |
| No database lock across execution | Evaluation blocked on a latch while an independent SQLite writer and cancellation both commit |
| Crash/restart and fencing | PREPARED/CLAIMED/EXECUTED/STAGED/BEFORE_COMMIT/AFTER_COMMIT faults, new coordinator instances, dead/live process owners, stale fence, retry-budget exhaustion, real orphan process termination |
| Durable evaluation/evidence linkage | Default same-connection lookup, forged artifact digest rejection, source aliases for both existing attempt tables, explicit correction/retraction without extra attempts |
| Exposure and assistance | Solution reveal and legacy session explanation before freeze, repeat classification, immutable context codec and original registry regression tests |
| Catalog mismatch / unavailable tools | Exact assets missing, unsupported evaluator version, finalized replay surviving removed assets |
| Migration and old history | `ChallengeEvaluationMigrationTest` compares every pre-existing table before/after migration 16 and repeated startup; forced DDL failure rolls back version/tables; older downgrade fixtures still test their original upgrade boundaries |
| Ordinary problem/session/draft/weekly behavior | Existing focused regression suites, reserved assessment reporting isolation, full suite |
| Peer privacy before signing | `EvolutionPeerPrivacyTest`: ordinary AC baseline compared with local and forged AC/ACX/WA rows across all completion origins; daily/weekly/other-zone aggregates and signed preparation payloads remain byte-identical |
| Wire confidentiality | Real Ed25519 signing, frame encode/decode and signature validation; private source/command/evidence markers absent from signed bodies; unchanged consent and wire schemas |

## Review decisions and limits

- Evidence is sourced from immutable evaluation revisions, not from an editable draft or a manual
  verdict. Only one source root is effective per deliberate command; checks, remapping and corrections
  do not create independent solves. Both legacy source aliases remain suppressed after withdrawal.
- Atomicity spans evaluation revision, source attempt, aliases, final state and evidence append.
  Execution staging is separate. Fault tests distinguish before-commit rollback from after-commit
  response loss. No XP, mutable draft, session completion or mastery updates are included.
- Existing ordinary snapshot queries filter the reserved platform in **all three** source branches,
  including UNKNOWN-origin availability. Removing a field from a wire message was not used as a fix.
  Signed bytes are unchanged after inserting the adversarial private rows.
- The local runner is not a security sandbox. Heap/time/output limits and source admission support
  grading integrity under the installed-local-content threat model, not execution of arbitrary
  downloaded/peer code. OS-start/checkpoint crash gaps, temporary files after hard crashes and
  in-process JDK inspection limits are documented in the implementation contract.
- No physical power-cut simulation, Windows/macOS process-lifecycle validation, human learning-gain
  study, hostile-host containment or universal schedule proof has been performed. UI presentation
  and restart controls remain #216 work. #212 must consume unavailable/excluded/repeated observations
  honestly instead of turning bounded PASSED into an automatic proficiency estimate.

## Confirmed findings addressed during review

| Finding | Correction and regression coverage |
| --- | --- |
| A final array of atomic objects could resemble one fixed scalar atomic field | Resolve the exact field type and receiver symbol; reject replaceable/array receivers from the scalar mechanism even when finite I/O happens to pass |
| Process-start persistence could postpone the start of a timeout countdown | Use one monotonic deadline from process start, including registration/input setup; a deliberately slow registration cannot extend compilation |
| A cleared process locale could corrupt non-ASCII native filename arguments | Keep the environment restricted and set fixed UTF-8 locale values, matching explicit source encoding and UTF-8 stream capture |
| Old attempt-number gaps could collide with count-plus-one allocation | Allocate the next local attempt from the maximum preserved number under the existing writer transaction |
| A recorded legacy session explanation could be reduced to familiarity alone | Freeze that exact hint level and preserve assisted provenance in the canonical observation |

No confirmed P0/P1 grading, transaction, idempotency or peer privacy blocker remains within the
supported local evaluator scope after these corrections. The limitations above remain explicit;
unknown mechanisms never gain a verified completion to conceal missing support.

## Commands and results

Use Java 21 with Maven 3.9. Local runs use a mirror settings file and system CA trust solely for this
environment; the repository CI command remains `mvn -B test`.

Focused command:

```sh
mvn -B -Dtest='JavaChallengeEvaluatorTest,ChallengeEvaluationIntegrationTest,ChallengeEvaluationMigrationTest,EvolutionPeerPrivacyTest,*Evidence*,*Quest*,*SkillCatalog*,SchemaMigratorTest,JavaCodeRunner*Test,*ProblemSolving*,*JavaSolution*,*ProgressSnapshot*,*SnapshotPublication*,*PeerSyncOutbox*,*WeeklyAssessment*' test
```

Focused/full final counts and CI/head are recorded below after final verification. The original
foundation and #213 verification documents describe those earlier snapshots and do not claim to be
this implementation's test report. No test assertions were weakened and no tests were disabled. Existing JavaFX toolkit-dependent
classes retain their pre-existing headless assumptions; this environment has no display, so those
controller UI-state cases were not executed. Headless Maven counts do not establish GUI validation.


Final local verification (Linux, Java 21.0.12.1): **1,482 tests passed**, 0 failures, 0 errors,
0 reported test skips. The broad focused run passed **321 tests**; the subsequent compiler/source
invariant run passed **42 tests** (overlapping coverage). The final full run includes all 82 new
cases and the unchanged 1,400-test baseline. Existing toolkit-gated classes report zero cases without
a display, as explained above; no GUI execution is claimed. `git diff --check`, relative documentation
link checks and the root-database preservation checks pass. Final commit and GitHub CI result are
recorded on the review PR; no merge is performed.
