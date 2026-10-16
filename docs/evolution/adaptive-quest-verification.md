# EV-07 verification and adversarial review

See [implemented policy, persistence and APIs](adaptive-quest-policy.md). Based on merged #231 at
`e9d05e457372c5de011169572fab50f5ceedb2ec`; additive migration 18 follows head 17.

## Acceptance mapping

| #215 / requested behavior | Evidence |
| --- | --- |
| Deterministic versioned policy, approved order | `AdaptiveQuestPolicyTest`: unknown, genuine single/repeated failure, assistance, stale/contradictory support, transfer, due retention and supported progression; same input and reversed candidate order produce identical results/bytes |
| Real committed evidence drives distinct actions | `AdaptiveQuestIntegrationTest`: packaged Java references/mutants through the existing evaluator, canonical evidence and actual estimate revisions; replay is not another failure/family |
| Assistance, familiarity and family inflation | Assisted/revealed real submissions, fresh-family filters, exposed hold-outs, correlated command fixtures and existing mastery regressions; all exact estimate IDs and check/evidence references retained |
| No unavailable result counted as learner failure | Actual valid ReentrantLock solution is UNSUPPORTED; estimator stays UNKNOWN; unavailable runner/assets block affected starts and recommendations |
| Correct cadence and bounded advancement | Exact seven-day UTC boundary; unknown/future activity; hints, reveals, exposure, prepared attempts and legacy rehearsal; real transfer history and new prepared rehearsal postpone retention |
| No candidate relaxations | Missing behaviors, prerequisite support, budget, absent exact graph, declined/deferred task, exposed family, missing asset, unsupported runner, pilot pool and difficulty exclusions |
| Learner control without penalty | Durable accept, alternative, exploratory practice, decline/reversal, defer/due/resume, pause/resume, goal/budget/track change; no attempt/evidence write by choice; reset retains choices |
| Curriculum ordering | Real approved workbook import; the exact no-argument `ProblemLibraryService.getNextRecommendedProblem()` delegates once and remains the same frontier across track switching; existing import critical path also runs in the full suite |
| No exposure during suggestion/accept | Zero presentation/submission/evidence before start; existing baseline identity and Java session/draft used only after the same-transaction authorization boundary |
| Historical replay and idempotency | Stable decision/checksums, persisted output verified against pinned V1, restart before/after accept/start, same-command conflicts, concurrent recommendations/responses/starts, unknown historical policy and corrupted digest rejection |
| Correction and snapshot consistency | Real source withdrawal injected between read and publication retries without stale positive; earlier decisions survive withdrawal, remapping and epoch reset; historical as-of excludes future performance |
| Safe additive migration | `AdaptationMigrationTest`: populated v17 byte/row snapshot, repeated startup, foreign-key check, partial DDL rollback/retry, immutable table guards; older migration fixtures preserve their original learner-row assertions |
| P2P and ordinary compatibility | Extended `EvolutionPeerPrivacyTest` verifies unchanged ordinary aggregates and byte-identical signed daily, weekly, alternate-zone and preparation payloads after decisions/controls plus real evaluations/corrections/reset |
| UI handoff | Exact immutable decision/revision refs, structured reasons/rankings, local choice history and existing presentation/evaluation services; no new JavaFX screen or generic route bypass |

## Adversarial review findings

| Concern | Resolution or explicit limit |
| --- | --- |
| One replay/check/mapping becomes another independent solve | The existing estimator owns proof eligibility/correlation. Adaptation consumes its contribution metadata; duplicate submission metadata collapses, conflicts reject. It writes no observation. |
| Familiar/assisted harder result raises the ceiling | Fixed in review: advancement uses independent proof difficulty, not the latest familiar/assisted task. Regression fixtures cover transfer, progression, diversity and consolidation. |
| False harder unlock from assistance, stale band or withdrawn proof | Ordered consolidation/validation branches and current prerequisite checks; exact source corrections re-read under SQLite snapshot/version fencing. No XP or streak input. |
| Reserved task exposed merely by suggesting it | Decisions contain metadata only, no prompts, harnesses, keys or source. Accept is separate from start. Only start commits exposure before returning content. |
| Accepted old decision bypasses a later pause/exposure/missing asset | Start recomputes current eligibility and uses the same short writer transaction for authorization and the existing presentation append. Already exposed receipts resume without renewed novelty claims. |
| Renamed variant defeats a learner decline/defer | Preference filters suppress related installed family variants too. Explicit reversal clears the original choice; new content/version is not an implicit reversal. |
| A future retention due date blocks other supported work | H matches only an actually eligible due check. Otherwise K may suggest another supported action; due time and the rehearsal trade-off remain visible. |
| Familiar hold-out becomes unseen because the fresh pool depleted | Every reserved task requires its own unfamiliar eligible family and curated equivalence; no pool relabeling/fallback. PILOT_POST cannot be explored through this service. |
| Endless forced retry after repeated failure | Decompose/lower/fresh support; a family with two prior commands is excluded from forced repeated-failure recommendations. Rest/change direction and explicit labeled practice remain optional. |
| Mixed estimates from incompatible snapshots | New opaque repository-backed mastery capture reads all four inputs on one connection; preparation is outside SQL, publication is version-checked. Adaptation does not accept caller-fabricated durable estimate inputs. |
| Historical decision silently changes with new code/defaults | Frozen canonical input AND output have separate SHA-256 checks. Pinned V1 replay must equal stored output; unknown interpreters and mismatches fail closed. No hypothetical V2 engine or #232 registry is implemented. |
| Evaluation rolls back if selection fails | No evaluator transaction calls adaptation. The application invokes it after commit; failures leave real results/evidence intact and recommendation pending. |
| Imported workbook order or peer payload changes | Ordinary selection delegates exactly; no workbook/source/aggregate/wire query changes. Real import and signed privacy regressions cover the boundary. |

No confirmed remaining P0/P1 blocker is known after these checks. The installed catalog cannot
supply independent prerequisite proof for every skill: reproduction has one repair-practice family,
liveness one practice family. A downstream estimate can be independently supported while prerequisite
recommendation eligibility remains insufficient. Return the recorded limitation and labeled optional
practice; do not silently weaken the graph, invent content or modify immutable v1 assets.

Other limits: Linux Java 21 verification only; local familiarity/help declarations are not proctoring;
runner limits are not sandboxing; existing admitted Java forms and hard-crash process limitations remain;
immutable local metadata accumulates; bounded histories return an error when exceeded. New historical
queries use current effective evidence, not invented historical knowledge time. No desktop-rendering,
Windows/macOS, physical power-loss or learning-effectiveness claim is made. Existing deferred
#221/#222/#224/#225/#228/#229/#230/#232/#233 remain unchanged.

## Commands and results

Every integration/migration test uses `IsolatedDatabaseExtension`, the shared test database resource
lock and temporary SQLite files. Fixed UTC instants are inside their windows; no wall-clock-relative
fixture is used for the adaptation policy. The existing JavaFX headless prerequisites are unchanged.

```sh
mvn -B -Duser.timezone=Pacific/Honolulu \
  -Dtest=AdaptiveQuestPolicyTest,AdaptiveQuestIntegrationTest,AdaptationMigrationTest,EvolutionPeerPrivacyTest,MasteryIntegrationTest,OrdinalMasteryPolicyTest,MasteryMigrationTest test
mvn -B test
git diff --check
```

Final timezone-focused selection: **150 tests passed**, zero failures, errors or reported skips,
Java 21 with `Pacific/Honolulu`, **1 minute 23 seconds**. This includes 40 pure adaptation fixtures,
27 real adaptation integrations, 3 new migration checks, 1 signed privacy regression and 79 existing
mastery/estimate/migration regressions.

Complete-suite totals and exact-head GitHub Actions status are recorded in the pull request for this
branch. Check its final SHA rather than an earlier passing run. The five existing headless GUI classes
may report zero executed cases; desktop rendering is not claimed as verified. No tests or assertions
were disabled/weakened, and the repository-root learner database remains absent.
