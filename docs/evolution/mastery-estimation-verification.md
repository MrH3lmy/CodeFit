# EV-04 verification and review

See [implemented architecture/policy](mastery-estimation.md). Based on merged #227 at
`99015651de526653040627ca98f435bbd831c955`; remote main was rechecked before publication.
Migration 17 is additive and does not replace original learner rows or published content.

## Acceptance coverage

| #212 / requested deliverable | Implementation and verification |
| --- | --- |
| Fresh/low/missing sample states; all four pilot skills | `OrdinalMasteryPolicyTest`, `MasteryIntegrationTest.freshAllFourSkillsAndSkippedBaselineStayUnknownAcrossRestart`; no XP/card mastery input |
| Approved ordinal policy and exact behavior coverage | Pure decision-table fixtures for same/different families, baseline-only, non-baseline cohort threshold without reusing transfer proof, missing behaviors, diagnostic scope, transfer, retention and post-pool separation |
| Hints, reveals, unknown help/exposure and self-report | Pure assistance/exposure parameter fixtures and real submission/registry integration; prospective/historical assessment distinction |
| Authoritative effective evidence only | Same-connection `forSkill` + `evaluationInput`, canonical metadata equality, original-root/submission/family grouping; source/mapping/alias regression tests |
| No inflated check/variant/mapping/replay units | Multiple-check, repeated variant, remapping, corrected/withdrawn and deliberate command replay fixtures |
| Honest partial/unavailable/failure interpretation | Pure unavailable check/status fixtures and real packaged mutants; optional observed counterexample contradicts while optional passes cannot establish missing required coverage |
| Contradiction/regression/restoration | One/two failure, replay-versus-deliberate retry, restoration requiring fresh families/transfer, unsupported-not-failure fixtures; real mutant sequence retains successful history |
| Explicit UTC/freshness/historical queries | Inclusive 30-day boundary, future/unknown times, deterministic same-time ordering, stale independent support, fixed-clock real records and alternate JVM timezone |
| Delayed retention | Exact 7-day boundary; intervening exposure/instruction, unfinished submission and un-ingested mapped review; unknown rehearsal timestamps and opened-task rehearsal fail closed |
| Reset and auditable deterministic revisions | Equal-time/source watermark, remapping after reset, restart/history, unchanged-input revision reuse and two-thread recomputation |
| Coherent snapshots and mapping transitions | Correction injected after read/before publish retries safely; pending replacement interpretations block advancement; no fallback to old mapping positives |
| Usable baseline API | Existing keyed and Java diagnostics, exposure before view, re-entry/skip/resume, persisted command history, no duplicate receipt/attempt or baseline grader |
| Prospective #104 facts without retrospective provenance | `AssessmentProspectiveFactsTest`: validator scope, subjective Java, exact variant/time/aid, attempt/facts rollback, forged historical provenance rejected by source validation |
| Migration and repeated startup preserve history | `MasteryMigrationTest`: populated v16 row snapshot, partial-DDL rollback/retry, foreign keys and empty new stores; older upgrade tests retain original source preservation assertions |
| P2P/ordinary behavior | Extended `EvolutionPeerPrivacyTest`: same signed day/week/timezone/preparation payloads and ordinary totals after evaluation, correction, withdrawal, estimates, reset and skip; full legacy regressions |
| #215/#216 handoff | Revision IDs/policy/as-of, behavior/check/failure refs, eligibility and structured reasons; informational next needs only, no adaptive selection or new screens |

## Adversarial review

| Risk | Resolution / retained limitation |
| --- | --- |
| One hundred familiar solves become independent proficiency | Positive proof collapses by source/command/attempt/family and requires frozen declared novelty. Related variants and repeated submissions cannot add diversity. |
| Old positives survive withdrawal or superseding mapping | Existing effective views and exact evaluator revision comparison exclude them. A source problem is not mislabeled learner regression; original estimate revisions remain inspectable. |
| New mapping is only partly populated | Known older interpretations awaiting the explicit replacement block current advancement, including when other positive families exist. No silent fallback. |
| A stress pass or diagnosis alone proves atomicity | Only actual relevant required checks support behavior; keyed-only diagnosis cannot establish independent code repair. An actual optional counterexample can refute supported behavior. No new evaluator claims. |
| Assisted/revealed or unknown conditions become independent | Known no-aid declaration plus receipt/submission-time registry conditions are required. Recent assisted/familiar practice cannot alone refresh old independent capability for advancement. |
| Retention ignores failed/unfinished practice or ingestion lag | Relevant durable exposure, prepared commands and existing source-adapter activity are included; ambiguous time fails closed. External activity remains unobserved, explicitly limited to recorded scope. |
| Reset restores familiar/old evidence through equal clocks/remapping | UTC cutoff plus original-source publication watermark; exact-time ambiguity excluded. Exposure and source history survive. Backward reset time rejects. |
| Concurrent correction/mapping/reset combines incompatible states | One read snapshot, release before policy work, same-connection data-version check under writer lock, bounded retry. Unchanged revisions avoid writes. |
| Historical as-of resurrects known-broken proof | Recompute filters current effective knowledge by event time. Saved earlier revisions show earlier conclusions. No invented bitemporal history from ingestion timestamps. |
| Private artifacts or estimates reach peers | Metadata-only snapshot/API; no peer/source projection changes. Signed payload byte-equality test covers new derived writes. |
| Migration changes old learning records | New tables/index/guards only. No content mutation, backfill, XP, curriculum, scheduler or source-row rewrite. Historical tests remove only later tables in isolated fixtures. |

No confirmed remaining P0/P1 blocker was identified after the corrections above. Limits remain explicit:
finite/uneven family coverage (especially reproduction/liveness), local declarations and locally inspectable
assets, bounded input sizes/retries, current-effective historical semantics, immutable metadata growth,
and the existing evaluator's admitted solution forms and process guarantees. These are not learning-gain,
security-sandbox or universal thread-safety claims. #228/#229/#230 remain deferred and unchanged.

## Checks

Java 21 commands use the existing Maven project; every SQLite test uses an isolated database and the
shared database resource lock. No test was disabled or assertion weakened; no test touches a real learner database.

```sh
mvn -B -Dtest='*Mastery*Test,*Evidence*Test,*Quest*Test,*Challenge*Test,*Assessment*Test,SkillCatalogMigrationTest,JavaSolution*Test,ProblemSolving*Test,*Snapshot*Test,PeerComparisonServiceTest' test
mvn -B -Duser.timezone=Pacific/Honolulu -Dtest=OrdinalMasteryPolicyTest,MasteryIntegrationTest,AssessmentProspectiveFactsTest,MasteryMigrationTest,EvidenceRepositoryTest,EvolutionPeerPrivacyTest test
mvn -B test
git diff --check
```

Final timezone-focused run: **100 tests passed**, zero failures/errors/reported skips. Complete Java 21
suite: **1,566 passed**, zero failures/errors/reported skips. Detailed final results follow.

Desktop manual rendering, Windows/macOS execution, physical power loss and learning outcomes were not
verified. Existing JavaFX headless prerequisites may yield zero discovered cases for those GUI classes;
zero Maven-reported skips is not a claim of GUI coverage. No test was disabled for this change.

Next: **#215** owns explainable adaptive selection based on the new conservative revision contracts.
No duplicate follow-up issue, new sharing contract or incidental #228/#229/#230 implementation is needed.

## Final local results

- Final timezone-focused command: **100 tests passed**, zero failures/errors/reported skips, Java 21,
  `Pacific/Honolulu` (55 pure policy, 22 real estimator/baseline integrations, 5 prospective assessment,
  2 migration, 15 existing evidence repository and 1 signed P2P isolation test).
- Complete Java 21 `mvn -B test`: **1,566 tests passed**, zero failures/errors/reported skips,
  **6 minutes 17 seconds**. This includes **84 new tests** over merged-main's 1,482.
- 194 XML test reports: 189 have executed cases; the five existing headless GUI classes report zero
  cases. Their prerequisites were not changed, and desktop rendering is not claimed as verified.
- `git diff --check`, new/updated documentation relative links, and isolated database guards pass.
  Repository-root `codefit.db` remains absent, matching its initial state.
- Remote main remains `99015651de526653040627ca98f435bbd831c955`; migration 17 has no current conflict.
  Exact-head GitHub Actions status is recorded in [PR #231](https://github.com/MrH3lmy/CodeFit/pull/231).
  The PR must remain unmerged for review.

The ordinal threshold is at least one eligible opportunity outside BASELINE, irrespective of its retained
cohort. A TRANSFER result can support the underlying independent band, but TRANSFER_SUPPORTED requires
two other prior qualifying families; it cannot be its own prerequisite. Pure fixtures and a real
baseline + transfer integration verify both the approved threshold and the separate transfer requirement.
