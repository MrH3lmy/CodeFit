# EV-10: preregistered Java learning feasibility protocol

**Status: protocol draft; not preregistered, approved, or executed.** This document defines a
feasibility study for [#218](https://github.com/MrH3lmy/CodeFit/issues/218) under
[epic #208](https://github.com/MrH3lmy/CodeFit/issues/208). It contains no participant outcomes.
It is a design contract, not evidence that adaptive practice works. No enrollment or outcome review
may begin until the freeze gates below are satisfied and this protocol is committed with its exact
version and content manifest references.

## Question and limits

Among opt-in learners practicing the installed Java Debugging & Concurrency curriculum, does an
adaptive sequence produce better independently observed performance on predeclared, unseen,
equivalent Java tasks than a fixed sequence when practice time, task pool, evaluator, assessment,
and reward conditions are held equal? Are those observations retained on a genuinely delayed task
and repeated in a separate held-out transfer context?

This is a small feasibility comparison, not a powered efficacy trial. It cannot establish clinical
or broad cognitive change, prove absence of outside help, validate the `ordinal-v1` bands, or support
automatic extrapolation beyond Java. A positive direction can justify planning a larger Java study,
not a claim of proven benefit or a cross-domain launch.

## Preregistration and freeze gates

Before the first study invitation and before anyone with study access sees outcome data, commit a
completed protocol version and a freeze record containing:

1. The protocol commit and SHA-256, study start/end window, eligibility/recruitment text, target and
   maximum enrollment, randomization seed, allocation list owner, and analysis code/version or
   hand-checkable analysis specification.
2. Exact CodeFit commit, Java/JDK vendor and patch version, OS/architecture, database schema version,
   challenge/quest IDs and versions, manifest and asset digests, evaluator/check versions, estimate
   and adaptation versions/configuration, fixed-arm sequence, outcome rubric, and reward policy/
   condition. Record the actual runtime versions with each session.
3. The completed task-equivalence review, assignment map, content-access check, and signed review
   checklist at the end of this document.

The freeze record is versioned and retained locally with the study records. Any change after the
freeze but before enrollment requires a new version and renewed review. After enrollment, changes
that could affect assignment, measurement, outcome handling, or reward conditions stop enrollment;
the deviation is dated and reported, and the original analysis remains primary. No cutoff, task,
exclusion, or outcome may be retuned after inspecting results.

**Execution is currently blocked.** The catalog has no demonstrated equivalent baseline/post form
set: `baseline-counter` targets atomic update without invariant coverage, while both pilot-post items
target atomic update and invariant; the trace diagnostics target reproduction. The existing transfer
and retention cohorts each contain only one family/task. Content review must supply and independently
approve sufficient equivalent forms, a transfer task, and a delayed task with required behavior
coverage before this protocol can be frozen. A cohort label, matching difficulty number, or
similarity of names does not establish equivalence.

## Task cohorts and exposure separation

The task assignment list is frozen before enrollment. The following is the current catalog inventory,
not an approved equivalence claim:

| Role | Current Java v1 identity | Use and limitation |
| --- | --- | --- |
| Baseline candidates | `baseline-trace/v1`, `baseline-localize/v1`, `baseline-counter/v1` | BASELINE only. `baseline-trace` and `baseline-localize` share `trace-diagnostic`; they are not independent families. They do not currently form equivalent forms for all post-test skills. |
| Daily-practice pool | `counter-repair/v1`, `counter-repair-b/v1`, `atomic-boundary-drill/v1`, `remaining-capacity/v1`, `distinct-id-accumulator/v1`, `regression-localize/v1`, `lock-order-repair/v1` | PRACTICE only. The counter variants are one family and must not be counted as independent solves. |
| Immediate post-test candidates | `post-ledger/v1`, `post-expiry/v1` | PILOT_POST only, never daily practice. Both target atomic update and invariant behaviors, but equivalence and adequate baseline counterparts are not established. |
| Held-out transfer candidate | `inventory-transfer/v1` | TRANSFER only. Do not use for practice, baseline, or post-test; one family is not enough for a general transfer claim. |
| Delayed retention candidate | `queue-retention/v1` | RETENTION only. Do not use for practice, baseline, post-test, or the transfer endpoint. One family is not enough for a general retention claim. |

Before execution, reviewers must replace candidate status with a frozen per-participant form map.
Each baseline/post form pair must cover the same explicitly listed observable behaviors and required
invariant checks, with comparable task demands, rubric/check counts, allowed resources, and ordinal
difficulty. Reviewers blind to outcomes approve the mapping and explain any residual difference.
Where equivalent forms cannot be authored and reviewed, the affected outcome is not run or is
reported as unavailable; it is not repaired statistically after the fact.

Baseline, PRACTICE, PILOT_POST, TRANSFER, and RETENTION form disjoint pools by role. No task or
family used in one role may serve another role for that learner. Reserve every assessment/transfer/
retention form from ordinary discovery, recommendations, and the fixed sequence. Record pre-study
familiarity and all local presentations, practice, hints, solution reveals, drafts/submissions where
available. First local presentation alone is not proof of unseen work. A known prior exposure or
familiarity invalidates an *unseen/independent* classification for that task; retain and report the
result as exposed rather than silently replacing it.

Both arms draw daily tasks from the same frozen PRACTICE pool. The fixed-arm order is
`regression-localize/v1`, `counter-repair/v1`, `counter-repair-b/v1`, `atomic-boundary-drill/v1`,
`remaining-capacity/v1`, `distinct-id-accumulator/v1`, `lock-order-repair/v1`; advance in this order
within the fixed time budget. The two counter variants are one family, not two independent solves.
The adaptive arm uses the exact pinned adaptive policy on the same eligible pool; retain each saved
decision's frozen input/output, exclusions, and participant choice, and verify deterministic replay
matches the saved output. Before enrollment, verify the actual installed policy can select only this
frozen pool under the assigned Java track; if not, execution is blocked
until the pool or adapter is reviewed and the protocol is re-frozen. Do not add tasks, change budgets,
or expose assessment items to compensate for a learner's result.

## Participants, allocation, and schedule

- Invite learners who voluntarily opt in to this local Java study. Record only eligibility and the
  minimum predeclared experience/familiarity variables needed to describe the sample. Participation,
  stopping, or withdrawal has no effect on ordinary CodeFit access or progression.
- Target 16 randomized participants (8 per arm); cap enrollment at 20. Randomize 1:1 after baseline,
  using a saved deterministic seed and a predeclared allocation procedure stratified by baseline
  performance where feasible. Keep the allocation list inaccessible to outcome scoring until the
  frozen data extract is ready. Report the seed and any assignment deviations.
- Schedule one baseline before allocation, four practice sessions over a two-week window, then
  immediate post-test and separate held-out transfer tasks after the final practice session. Present
  the distinct pools in a counterbalanced order fixed before enrollment. Schedule the retention-only
  task at a separate visit 7–28 days after the last relevant task attempt or instructional exposure,
  whichever is later. Use the same assessment instructions, form time limits, environment, and
  permitted resources in both arms. The same assessment feedback embargo in the next section applies
  to both arms.
- Set a practice budget of four 30-minute active-practice sessions (120 minutes total) in each arm.
  Time spent solving, requesting hints, reading practice feedback, and reviewing a practice solution
  counts against the same active-time budget; freeze and verify the timer captures these intervals.
  Exclude evaluator runtime and explicit pause/idle time under the same pinned timer definition;
  capture each session's actual active time, task, pause, and interruption. Report assigned-budget and
  actual-time comparisons separately; do not extend one arm's budget to reach a desired result. If the
  timer cannot consistently capture these intervals, execution is blocked until resolved; if discovered
  after enrollment, the time-equivalence gate fails and the outcome is inconclusive.
- Present transfer tasks only in the immediate held-out assessment and delayed retention tasks only
  at their separate retention visit; record actual days since the final relevant instructional
  exposure—including feedback, hints, and solution reveals—plus known in-app and learner-reported
  intervening practice. For a quiet-retention claim, require at least seven days since the latest
  same-behavior task attempt/presentation, practice instruction, hint, solution reveal, or feedback
  exposure. Immediate POST and TRANSFER attempts therefore count as rehearsal when they target the
  retention behavior; any later relevant attempt resets the interval.
  Any intervening or unknown rehearsal is retained as a contaminated/unknown retention observation,
  not a quiet-retention success. App records cannot observe all off-app activity.

### Feedback, assistance, and assessment embargo

Freeze the feedback/assistance matrix, content, and release rules before enrollment. The exact
evaluator, rubric/check versions, feedback wording/detail, hint ladder, solution artifact digests,
and exposure-record fields are part of the preregistration alongside the task manifests.

- **PRACTICE:** In both arms, after a deliberate PRACTICE submission has a finalized evaluation,
  show the same redacted overall outcome, required-check statuses, and standard rubric explanation.
  Freeze per-task which rubric details are shown and the exact hint ladder and solution access rules.
  While solving, learners may request that task's published hints, one step at a time. If a reference
  solution is published for a PRACTICE task, it is available only after that task's finalized
  submission. These options are learner-controlled and available under identical task-specific rules
  in both arms; usage is not forced or artificially matched. The adaptive arm may use the resulting
  recorded practice evidence under its pinned policy; the fixed sequence does not change in response.
  Do not present PRACTICE feedback, hints, or solutions containing assessment-pool answers or
  equivalent answer disclosure.
- **BASELINE, immediate POST, TRANSFER, and delayed RETENTION:** Administer as assessment tasks with
  the same frozen instructions and allowed resources in both arms. Do not provide hints, reference
  solutions, answer keys, correctness/partial-result feedback, check-by-check results, expected values,
  or explanations before or during these assessments. Before the release gate, do not expose
  assessment materials or results through recommendations, ordinary browsing, participant-visible
  journal/result/log screens, practice hints, exports, or another participant's session. Ask
  participants not to discuss assessment tasks or answers until the cohort-wide embargo lifts; record
  reported disclosures as exposure. These tasks and assets are locally inspectable, not secure exams:
  record known familiarity or disclosure, and never describe route separation as proof that a learner
  could not inspect local files.
- **Release gate:** Withhold all participant-visible assessment feedback and keys—including baseline,
  POST, and TRANSFER—for the entire cohort until every randomized participant has either submitted
  the delayed RETENTION task and its evaluation is durably fixed, withdrawn/declined further
  assessments, or reached the end of the predeclared 28-day retention window. Then release feedback
  for all assessment tasks together. A participant's withdrawal, early completion, or individual
  feedback release must not lift the embargo for anyone else. If no valid retention submission exists
  when a participant's follow-up ends, that outcome remains withdrawn/missing, not a failure. Never
  reveal delayed task feedback before its own submission is fixed. Feedback released after the
  cohort-wide gate is subsequent learning exposure and cannot alter any already-fixed study outcome.
- **Exposure audit:** For each arm and task, freeze and retain locally the assignment, task/family/
  manifest/rubric/evaluator versions, presentation/submission IDs, outcome and finalization time,
  feedback version/content category and shown time, hint level/events, solution access/time, familiarity
  and assistance declarations, embargo/release authorization and time, cohort-wide gate state, and any
  route or timing deviation. Record feedback permitted by assignment separately from feedback actually
  viewed.
  Reconcile the journal/exposure history against the preregistered matrix before analysis.
  Report actual exposure and deviations by arm; classify any assessment leak before the delayed
  submission as exposed/contaminated, not unseen or independent. Unknown exposure blocks an
  unqualified unseen/quiet-retention claim.

If fewer than 12 participants complete the immediate post-test, or fewer than six completers remain
in either arm, the between-arm feasibility comparison is **inconclusive**. Retention follow-up loss
is reported separately and cannot be hidden by the immediate-post completer count.

## Fair comparison and pinned versions

The comparison changes only sequence selection. Freeze and apply the same four-session time budget,
practice pool, baseline/post/transfer/retention instructions, evaluation/check versions, support
rules and feedback/assistance matrix, device/runtime requirements, and assessment scheduling to both
arms. The fixed arm receives the predeclared fixed sequence; the adaptive arm uses `adaptive-v1` with
`java-pilot-v1`. Both use
the exact pinned `ordinal-v1` inputs for adaptation. Record actual decisions and exclusions; do not
replay or hand-select an adaptive sequence.

Pin the installed Java graph/catalog and complete asset digests, `local-java-v1` evaluator, exact
rubrics/checks, CodeFit commit, JDK/runtime and OS, `ordinal-v1`, `adaptive-v1`, and
`java-pilot-v1`. Unsupported or changed versions are a protocol deviation and must not be silently
interpreted as the frozen version.

There is no study-specific, performance-contingent reward. Participation compensation, if any, is
identical by assignment and independent of correctness, speed, completion, and withdrawal. Before
execution, pin the existing reward system's exact policy/version and initial state; apply the same
conditions in both arms, or disable Evolution-specific rewards for both. If equal conditions cannot
be demonstrated, do not start the comparison. Report reward exposure separately from capability
outcomes.

## Outcomes and scoring

Publish the exact task-to-behavior map, required check IDs, scoring rules, and missing/unavailable
handling in the freeze record. The participant is the analysis unit; checks and retries are not
independent participants.

| Outcome | Predeclared report |
| --- | --- |
| Primary: independent correctness | For every first held-out post-test task, report full required-check pass/fail/partial/unavailable. A task counts as an independent full solve only when it is a first recorded family presentation, the learner reports no prior familiarity or outside help, no in-app hint/reveal was used, and required checks pass. Summarize the per-person percentage of eligible mapped tasks solved independently, then the difference in baseline-to-post change between assigned arms. Report exposed/assistance-unknown cases separately, not as independent successes. |
| Correctness and partial outcomes | Report full pass, partial, observed required-check failure, unsupported, unavailable, timeout, cancellation, evaluator error, and no submission separately per task and arm. Do not turn unavailable outcomes into learner failures or omit them from the flow/attrition table. |
| Invariant/behavior checks | Report each predeclared required invariant and behavior check separately (passed / attempted and participant-level numerator/denominator), plus task-level full-rubric result. Do not combine check rows into an inflated solve count. |
| Hints and assistance | Report in-app hint count/levels, solution reveal, declared outside assistance, known familiarity, and unknown/missing declarations separately by arm and task. Unknown aid is not “no help.” |
| Active time and practice dose | Report per-person active practice minutes, assessment active minutes, pauses, session count, and task time distribution separately. Time is descriptive, not a speed score or proof of capability. |
| Quality | Report an item only if a quality rubric, exact version, scoring anchors, and independent scoring/reliability procedure were frozen before enrollment. Current reflection is ungraded; absent such a rubric, state “not measured” rather than infer quality from prose, XP, or elapsed time. |
| Transfer and retention | Report independent correctness and invariant checks for their own reserved tasks, separately from post-test. For retention report actual elapsed days and intervening practice/assistance status. Do not relabel a post-test score as transfer or retention. |
| Qualitative feedback | After all outcome tasks, invite optional responses to frozen prompts on task clarity, perceived difficulty/relevance, and usability. Summarize coded themes and disagreement separately from performance; report nonresponse and do not use self-reported confidence as capability evidence. |

Local declarations are not proctoring. Independent describes the recorded conditions above, not
verified authorship or absence of unobserved external help. No attempt, check, or assessment is
silently removed because it weakens an outcome.

## Analysis, uncertainty, attrition, and confounders

Analyze participants by randomized assignment (intention-to-treat). For the primary endpoint report
each arm's participant-level baseline and post distributions, each participant's change, arm-specific
median and range, and the between-arm difference in mean change in percentage points. Include a
participant-level 95% bootstrap interval using a frozen resampling seed and method; with this small
sample the interval is descriptive and unstable, not evidence of a calibrated effect. Resample
participants with replacement within each assigned arm for 10,000 replicates, keep each participant's
baseline/post pair together, and report the 2.5th and 97.5th percentiles of the difference in mean
change; freeze and report the bootstrap seed. Provide all
individual anonymized values or counts needed to audit the summary. Do not use tasks/checks as
independent sample units or rely on a p-value as a go/no-go test.

Report the same arm-by-arm distributions and differences separately for independent solves,
invariant checks, hint/assistance, active time, quality (if measured), transfer, and retention.
Show the number assigned, started, completing each visit, and included in each denominator by arm,
with withdrawal/noncompletion timing and voluntarily supplied reasons. Missing outcomes remain
missing. Report completer results alongside worst/best possible bounds for missing primary outcomes
on the frozen 0–100 scale; do not use complete cases alone to imply benefit.

Predeclare and report likely confounders without post-hoc adjustment: prior Java/concurrency
experience, task familiarity/exposure, outside practice or assistance, baseline imbalance, time-on-
task, device/JDK/runtime failures, interruptions, differential attrition, assessment-form difficulty,
and inability to verify off-app rehearsal. Present raw results first. Any adjusted/exploratory
analysis is labeled exploratory and cannot change the decision.

## Local collection, consent, and export

Study collection is opt-in and local-only. Explain purpose, task/outcome fields, retention period,
withdrawal/deletion route, and export choices before collection. Do not make study participation,
external export, or an account prerequisite for CodeFit. Store no central service or telemetry copy;
do not add or use P2P publication, peer aggregates, or automatic analytics. Preserve the existing
privacy boundaries in [#104](../epic-164-acceptance.md), [#164](../epic-164-acceptance.md), and
[#179](../p2p/adr-0001-decentralized-peer-architecture.md).

If a participant intentionally exports, let them preview and choose a bounded, private-safe summary
locally. Include only a participant-chosen pseudonym, frozen protocol/version and task identifiers,
arm, aggregate/check outcomes, assistance/exposure categories, active-time summaries, elapsed-delay
days, and attrition/deviation flags needed to interpret the results. Omit real identity, contact
details, exact local paths, raw code, answers, prompts/notes, reflections, answer keys, hidden test
inputs/expected outputs, raw qualitative responses, free text, and peer/network data. No export occurs
until the participant explicitly selects it; no remote destination is preselected. Export does not
delete or automatically upload the local source record.

For a pooled participant-level analysis, an analyst may combine only individually authorized export
bundles, using the participant-chosen pseudonym to keep each person's baseline/post pair together
and counting that person once. Validate protocol/version, assignment, task-role, and outcome-status
fields before combining; do not infer absent outcome values from an absent row. Keep the consented
enrollment/assignment flow record separate from outcome exports. Non-exporters are not study
withdrawals or outcome failures merely because of export choice; their records remain local and are
not included in an export-based pooled dataset. Report export coverage separately only to the extent
participants authorized it. If the available, authorized records do not meet the frozen sample,
attrition, or endpoint requirements, report the pooled result as incomplete/inconclusive—not as an
intention-to-treat result for the full cohort. No full-cohort aggregate may include or describe a
non-exporter's outcome without that participant's intentional export.

## Feasibility decision rules

These gates are declared before outcomes. They are operational thresholds for whether a larger
Java-only evaluation is feasible, not validated efficacy thresholds.

**Proceed to a larger preregistered Java study only if all are true:**

1. At least 12 immediate-post completers, at least six per assigned arm, and at least 75% of
   randomized participants complete the immediate post-test; the absolute arm attrition difference
   is no greater than 15 percentage points.
2. At least 90% of scheduled assessment tasks produce interpretable evaluator outcomes, with no
   unresolved task leakage, evaluator/version mismatch, or unequal assessment access.
3. Median delivered active practice is within 10% between arms, both arms use the frozen reward
   condition, and no privacy/consent boundary is breached.
4. The primary between-arm difference in baseline-to-post independent correctness is at least
   +10 percentage points, and neither held-out transfer nor eligible quiet-retention independent
   correctness is directionally worse in the adaptive arm. Report the uncertainty interval and
   every disagreement among these outcomes; this directional screen is not a claim of efficacy.

If a sample, task-equivalence, exposure, measurement, attrition, time-equivalence, version, or privacy
gate fails—or the results miss the directional screen—the decision is **inconclusive/no-go to
expansion**. Preserve and report the failure; do not describe it as proof of no effect or tune the
thresholds. Any substantiated privacy breach stops collection and export pending review.

No outcome from this feasibility study alone authorizes expansion beyond the initial Java domain.
That would require separately reviewed, preregistered, adequately powered Java evidence and a new
domain-specific protocol/content/equivalence review. Cross-domain extrapolation is out of scope.

## Pre-execution verification and review checklist

- [ ] Protocol commit, version/hash, and analysis specification frozen before invitations or outcome access.
- [ ] #213 content review approves exact equivalent baseline/post forms, behavior/check map, and separate transfer and delayed-retention pools; current catalog limitations above are resolved.
- [ ] Every task/family is checked against ordinary browse/recommendation routes, fixed practice assignment, and all other assessment roles; participant prior exposure is captured and exclusion is auditable.
- [ ] #212/#214/#215/#216 execution dependencies are complete; challenge/evaluator/estimate/adaptation and local UI/journal behavior are verified for the exact frozen versions.
- [ ] Reward policy/version, initial conditions, allocation seed, timer definition, active-time budget, arm sequence and participant schedule are pinned and equal where specified.
- [ ] Independent reviewer signs the rubric/equivalence map before outcome review; unsupported/partial/unavailable cases and missing data are exercised on fixtures only.
- [ ] Consent, local-only collection, withdrawal/deletion, local storage, opt-in export preview, and explicit export exclusions are reviewed against #104/#164/#179; no automatic P2P/analytics path exists.
- [ ] Sample target, minimums, attrition, confounders, uncertainty summaries, go/no-go rules, and all deviation/failure reporting are approved unchanged before enrollment.
- [ ] After execution only: publish the full participant flow, deviations, incomplete outcomes, attrition, uncertainty, null/harmful/inconclusive findings, and unresolved limits before any expansion decision.
