# Implementation roadmap and ticket refinements

Reviewed complete bodies/comments of #210–#218 against the audited classes, not just their titles.
The foundation implements none of those tickets. [EV-02 implementation](skill-graph.md) now supplies
the skill catalog for #210; [EV-03](skill-evidence.md) supplies evidence for #211.
[EV-05](quest-catalog.md) now supplies #213 content/family/rubric definitions, backing-source bindings
and durable presentation exposure. [EV-06](challenge-evaluation.md) now supplies #214 durable
submission/evaluation, source/evidence finalization and peer aggregate isolation.
[EV-08](player-system-ui.md) now connects those foundations through the offline Player System,
authorized workspace and source-projected journal. Next implement **#217 reward entitlements**,
keeping engagement separate from capability; #218 still owns pilot outcome validation.
[EV-04](mastery-estimation.md) implements #212 baseline orchestration, conservative ordinal estimates,
coherent metadata snapshots, revisions/epochs and prospective assessment facts. #215 can consume these
exact revision references, eligibility flags, checked behaviors and reasons; it owns the next quest choice.
[EV-07](adaptive-quest-policy.md) implements #215 ordered next actions, coherent estimate/exposure
snapshots, durable decisions and learner controls. #216 consumes those application boundaries
and journal projections; no new selector, evaluator, estimate engine or exposure bypass was introduced.
Keep existing issues; no duplicate engines/tickets.

## Foundation recommendation: implement #210 first

After #209's contracts/reuse decisions are reviewed, implement
[#210](https://github.com/MrH3lmy/CodeFit/issues/210): stable skill IDs, observable behaviors, a tiny
Java Debugging & Concurrency prerequisite DAG, versioned definitions and isolated additive migration
coverage. Start at [DatabaseConfig](../../src/main/java/com/codefit/config/DatabaseConfig.java),
[SchemaMigrator](../../src/main/java/com/codefit/config/SchemaMigrator.java) and existing repository
conventions. Follow [IsolatedDatabaseExtension](../../src/test/java/com/codefit/testsupport/IsolatedDatabaseExtension.java).
It supplies the identifiers/versions both evidence and content must reference. Do not implement
estimate algorithms, rewards, UI, Java evaluator or peer integration in #210.

No migration number is preallocated; rebase before allocating it. Shared schema changes land in
reviewed sequence, not concurrent conflicting migrations. No agent delegation is required by this roadmap.

## Reviewed dependencies and boundaries

| Ticket | Existing dependency / problem | Refined sequence and concrete scope |
| --- | --- | --- |
| [#210 EV-02](https://github.com/MrH3lmy/CodeFit/issues/210) skill graph | After #209; correct | First implementation. Own Skill/GraphRef and observable behavior contracts, not Player/social identity or mastery. Test cycles/orphans/retirement/version pinning, deterministic traversal and unchanged old DB records. |
| [#211 EV-03](https://github.com/MrH3lmy/CodeFit/issues/211) evidence | After #209/#210; correct, but old sources cannot support promised distinctions uniformly | Own Evidence/source cursor/mapping/correction contracts. Adapt `ProblemAttemptRepository`, `ReviewHistoryRepository`, `AssessmentAttemptRepository`; read raw provenance, `hint_usage_recorded` and `CompletionOrigin`. Legacy assessment correctness stays LEGACY_UNSPECIFIED; historical per-attempt hints stay UNKNOWN. Accept later evaluation source contract without implementing evaluator. Backfill source history beyond dashboard limits, one effective observation per skill regardless of adapter/mapping version. |
| [#213 EV-05](https://github.com/MrH3lmy/CodeFit/issues/213) quest pack | After #209/#210; original sequence places baseline #212 before executable content exists | Land definitions/assets after #210/#211 shared contract vocabulary. Own versioned manifest, family/cohort exposure rules and mapping to non-roadmap Problems via `ProblemService`; no second attempt store. Supply independent practice families, held-out baseline/transfer/retention/post pools, licenses, sample fixes/mutants. Add catalog-aware exclusion from `ProblemLibraryService` Topics/distinct-topic suggestions; reserve Evolution assessment cohorts from weekly selection. Cannot claim executable acceptance until #214 verifies samples. |
| [#214 EV-06](https://github.com/MrH3lmy/CodeFit/issues/214) evaluation | After #211/#213; correct, but runner does not persist grading or provide an atomic submit API | Own submission/evaluation contracts and atomic Evolution-only completion using `CodeRunner`, `JavaExecutionCoordinator`, `ProblemAttemptRepository`, sessions/drafts and `Transactions`. Add local-verdict marker, immutable submission assistance/exposure and artifact snapshots, replay key, distinct failure outcomes. Narrow legacy dashboard external-verdict denominators and snapshot source filters to exclude new local challenge rows. No wire/consent/network changes. Verify content samples here; stress checks alone cannot substantiate deterministic concurrency claims. |
| [#212 EV-04](https://github.com/MrH3lmy/CodeFit/issues/212) baseline/estimates | Currently after #210/#211 only; executable baseline would otherwise need a second grader or self-rating | Pure estimate policy can be developed after #211; **complete ticket acceptance after #213/#214**. Add explicit dependency on both. Own estimate policy/revision/epoch and baseline orchestration through the evaluator, not new task/evaluation persistence. Add prospective grading/hint/exposure metadata to `AssessmentAttemptService`/controller for compatible #104 items, preserving unknown legacy fields and schedule isolation. |
| [#215 EV-07](https://github.com/MrH3lmy/CodeFit/issues/215) adaptation | After #212/#213/#214; correct | Own NextQuestDecision and persisted reason/input snapshot, deterministic branch table, cadence/budget/pause/defer. Compose `ProblemLibraryService`/`GuidedPracticeService` for curriculum; never replace `ReviewService` or infer independence from current progress. Journal decision records are added here rather than a second journal event store in #216. |
| [#216 EV-08](https://github.com/MrH3lmy/CodeFit/issues/216) UI | After #212/#214/#215; #213 is transitive; no explicit journal owner | Integrate with `Route`, `NavigationService`, `AppShellController`, existing workspace/controller themes. Own Player controls and journal projection. Show source/rubric/assistance/estimate/recommendation links, interrupted/pending/no-candidate states and scoped confidence. Verify hold-out route access and existing navigation. Offline full loop does not require new rewards; don't pull #217 into critical path. |
| [#217 EV-09](https://github.com/MrH3lmy/CodeFit/issues/217) progression | Text uses EV-03/EV-07 rather than concrete links | Normalize dependencies to #211/#215 (and evaluator event contract from #214, already transitive). Extend `ProgressService`/`UserProgressRepository`, preserve opening XP/streak balance. Own bounded entitlement ledger + atomic balance update, no new parallel XP total. Existing reward paths have crash gaps; don't claim they were fixed by a new ledger. No historical evidence backfill rewards or estimate input from XP. |
| [#218 EV-10](https://github.com/MrH3lmy/CodeFit/issues/218) pilot | After #212/#214/#215/#216; correct for execution, too late for reserving cohorts | Define protocol/pools before finalizing #213 assets; run only after complete UI loop. #218 owns outcome analysis/consented export, not a new analytics service. #217 optional for learning validation; if reward policy changes, pin it equally across comparator groups. Record evaluator/estimate/adaptation/content versions and deficiencies. |

Recommended merge sequence:

`#209 → #210 → #211 → #213 → #214 → #212 → #215 → #216`, then #217 and pilot execution #218.
The pilot protocol and hold-out requirements should be agreed during #213 content review, before
any outcome is observed. This sequence avoids a baseline/evaluator dependency cycle. #213's hand-run
sample validation becomes fully executable in #214; #212 does not own a second compiler/runner.

## Acceptance refinements to retain in existing issues

1. **#210:** immutable versions/retired IDs and explicit category mappings; no taxonomy membership
   implies proficiency; old data checked before/after repeat initialization. Shared contracts are
   domain-neutral; Java content belongs in curated definitions, not core enum branches.
2. **#211:** source identity includes revision/event kind; correction/mapping upgrades don't multiply
   evidence; per-submission check rows are not independent successes. Unavailable/deleted source
   cannot manufacture proof. Historical assessment grade/hints cannot be reconstructed; add unknowns.
3. **#213:** stable challenge/family/version/cohort and presentation registry, enough distinct practice
   families for two independent successes plus reserved transfer/retention/baseline/post pools. Seed
   only reserved-platform Problems without roadmap membership; exclude from Topics and ordinary
   recommendations. Keep #104 assessment bank separate and reuse its validators where applicable.
4. **#214:** original Java challenges cannot use AC as a local-pass shortcut. Persist one original
   ProblemAttempt plus evaluation/context, with `LOCAL_EVALUATION` or equivalent non-judge marker;
   regression-test enum consumers and external-judge denominators. Freeze artifact before run; run
   outside DB lock; finalize atomically with idempotent command; snapshot hints before reset.
   Explicitly exclude new challenge attempts from existing opted-in P2P aggregates. Test all snapshot
   query branches, including unknown origins and fresh unique completion, without protocol changes.
5. **#212:** baseline uses #213/#214; no subjective Java assessment promoted to verified code proof.
   Implement ordinal v1 and contradictory/stale/reset behavior with evidence IDs/versioned as-of.
   Prospective assessment metadata belongs at the source write; original schedules untouched.
6. **#215:** branch fixtures include cancellation/infrastructure errors versus failures, repeated
   deliberate failures versus replay, family exhaustion, correction, learner choice, stale evidence,
   cadence after rehearsal and exact curriculum frontier. Save exclusions as well as chosen reason.
7. **#216:** Evolution Journal is a projection, not copied answers/code or another activity log. Start
   from a fresh offline DB; inject restart at PREPARED, execution, commit, projection and reward
   boundaries (reward checks join #217 later). Explain no-data and unknowns accessibly.
8. **#217:** one versioned opening balance plus new entitlements, no replay/backfill reward; never
   decrement existing XP on a failed/pause event. Upgrade of reward policy has explicit prospective
   behavior and cannot reaward the same entitlement under a new version by accident.
9. **#218:** predeclare independent outcomes, held-out pools, delayed timing, exclusions/attrition,
   comparator and minimum interpretable sample. Report inconclusive results, no cross-domain claims.

These are refinements to the existing tickets, not authorization to implement them in this PR.
The issue updates point back to this foundation PR so future reviewers can see the proposed contract.

## Small implementation slices and checks

| Slice | Relevant real classes to extend/compose | Required proof before moving on |
| --- | --- | --- |
| Skill catalog (#210) | `SchemaMigrator`, `DatabaseConfig`, repository pattern | Populated upgrade/restart, DAG validation, no original-row changes |
| Evidence adapter (#211) | `ProblemAttemptRepository`, `ReviewHistoryRepository`, `AssessmentAttemptRepository` | Duplicate/backfill restart, unknown provenance, supersession/deletion, unchanged source state |
| Original content + evaluator (#213/#214) | `ProblemService`, `JavaSolutionWorkspaceService`, `CodeRunner`, `JavaExecutionCoordinator`, `Transactions` | Correct/wrong/mutant solutions, original assets, blocked hold-out browsing, duplicate/concurrent commands, crash recovery, P2P aggregate exclusion |
| Baseline/estimate (#212) | `AssessmentAttemptService`, `AssessmentGradingService`, new pure skill policy | Stable examples for success/fail/assisted/contradictory/stale/no-data; no schedule changes |
| Explainable loop (#215/#216) | `GuidedPracticeService`, `ProblemLibraryService`, `Route`, `NavigationService`, workspace/controller | Fresh-install offline integration, explainable journal links, user choices, ordered curriculum preserved |
| Rewards/pilot (#217/#218) | `ProgressService`, `UserProgressRepository`, pinned evaluator/policies | Transaction replay tests, preserved opening state; predefined real learning evaluation |

## EV-10 protocol and execution gate

The predeclared feasibility design, task inventory and known equivalence gaps, sample/attrition rules,
separate outcome measures, local-only collection/export, analysis, go/no-go thresholds, and
pre-execution checklist are in the [EV-10 learning validation protocol](learning-validation-protocol.md).
The protocol is a draft, not a preregistration or pilot. Do not enroll or inspect outcomes until its
freeze gates and the #212/#214/#215/#216 execution dependencies are satisfied. The #213 content review
must approve matched held-out forms first; the current baseline/post/transfer/retention inventory is
not enough to claim equivalence or execute the proposed comparison.

## Migration and architectural risks

Most significant risks are mistaken provenance (legacy assessment/self-report), correlated retries
inflating proficiency, flaky concurrency checks, non-atomic old write paths reused as if safe, accidental
hold-out exposure, and broad snapshot queries quietly sharing new data. The ADR and ticket additions
make these explicit acceptance requirements. There is no schema/runtime change to migrate in #209.
Follow-up migrations preserve all original records and coordinate with the current P2P migration head.
