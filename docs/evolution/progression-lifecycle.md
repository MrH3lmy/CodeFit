# Player progression lifecycle

**V1 design; baseline/estimate policy is implemented in [EV-04](mastery-estimation.md).** See [contracts](domain-contracts.md) for field/status meanings and the
[audit](existing-system-audit.md) for current behavior. All dates and outcomes in examples are fixtures.

## Exact V1 journey

1. **Enter Today.** Existing review/curriculum actions still work. Learner explicitly selects the
   Evolution Java Debugging & Concurrency track, a goal and active-time budget. No social identity,
   network or AI setup is required. Returning users see saved attempts/decisions; a fresh user sees
   "skill baseline unknown", not level-derived capability.
2. **Establish baseline.** Show what will be measured and the local runner limits. Reserve short
   original diagnostic tasks from the BASELINE pool, covering deterministic debugging, atomicity and
   liveness. Each presentation is recorded before content appears. Learner can skip all or individual
   tasks, disclose familiarity or ask for hints; skipped skills remain UNKNOWN. Reuse existing history
   as context with honest provenance, never as an automatic verified baseline.
3. **Identify opportunity.** Compute per-skill estimates from current effective evidence. Distinguish
   prerequisites with observed difficulty, skills not yet observed, and stale/contradictory performance.
   A positive diagnostic alone supports only provisional independent performance. No false precise score.
4. **Recommend one quest.** Give target behavior, task, effort estimate, difficulty/constraints and
   reason linked to diagnostic evidence (e.g. "compound update failed after a visibility-only fix").
   Offer accept, alternatives, defer, pause, change goal or return to ordered curriculum. For curriculum
   new work, the only candidate is `ProblemLibraryService.getNextRecommendedProblem()`.
5. **Attempt.** Reuse reading/thinking/coding/debugging phases, draft/editor and authored hint ladder.
   Capture prior exposure and assistance as events; no timer accrues while paused/closed. Artifact is
   frozen under a persisted submission command before evaluation. Optional practice runs are feedback,
   not separate independent skill successes. Actual Submit defines the evaluated observation.
6. **Evaluate.** Bundled original Java tasks use pinned local rubric/checks through existing runner.
   Show observed pass/fail/partial and limitations. Declared external/manual outcomes stay self-reported.
   Compilation, execution, cancellation, infrastructure error and failed criteria remain distinct.
7. **Persist evidence.** Atomic finalization stores one existing ProblemAttempt with a local-evaluation
   marker, its context/evaluation and per-skill evidence. Include failures and assistance/exposure.
   No result means no manufactured proof. Linked runner and attempt adapters cannot count twice.
8. **Revise estimate.** Recompute from effective supported observations only. Display prior/new band,
   confidence/freshness, sample/family count and reasons. An assisted success can identify useful
   practice without raising independent proficiency. Estimation failure does not discard the evaluation.
9. **Adapt.** Deterministic policy chooses retry, prerequisite practice, new independent validation,
   delayed retention or held-out transfer, with decision inputs/version saved. Difficulty, task type,
   cadence and learning direction may change. Show why and allow learner override.
10. **Repeat or stop.** Accepted next task begins another observation. Delays do not remove XP,
    punish users or manufacture failed attempts. Journal links observation → estimate → decision → choice.
    Existing engagement rewards remain separate; new Evolution rewards begin only in #217.

## Estimate policy v1: conservative, ordinal and replayable

An estimate contains `skillRef`, `epoch`, `band`, `supportLevel`, `freshness`, contributing/excluded
observation IDs with reasons, counts by provenance/assistance/exposure, last meaningful observation,
`asOf`, policy/config version, and contradiction flags. Support levels describe coverage, **not a
probability**: INSUFFICIENT, LIMITED, DIVERSE. No "87% mastery" display.

Eligible positive proof must have available, non-retracted LOCAL_CHECK evidence with passed checks
mapped to the skill's stated behaviors, a valid environment/rubric, no solution reveal and no disclosed
or observed aid for an independent claim. OBJECTIVE_REVIEW supports retention of that item only;
SELF_REPORTED/LEGACY_UNSPECIFIED and UNKNOWN assistance/exposure can guide optional diagnostic
selection but cannot raise independent/transfer bands. Authenticated external proof is unsupported
until a reviewed adapter exists. No inference of "no hints" from missing records.

A **proof unit** is a deliberate solve opportunity in an exposure family, not a test/check/row. An
independent new-family success can add one unit. Repeating the same task or its near variant never
adds an independent unit, though it may be a valid delayed retention check. A new variant in the same
family is RELATED_VARIANT. A transfer success requires reserved unseen task/family, predeclared
behavior equivalence and broader changed context, not renamed variables. Baseline, daily practice,
post-test and transfer cohorts never silently mix.

| Band | Rule under v1 | Support |
| --- | --- | --- |
| UNKNOWN | No eligible direct skill observation; skipped/inconclusive/self-report-only history | INSUFFICIENT |
| DEVELOPING | Eligible failure/partial on the skill or assisted-only checked success; feedback identifies what is supported | LIMITED; failures alone never mean zero ability |
| INDEPENDENT | At least 2 distinct-family independent passed opportunities, at least one outside the baseline pool, covering all required behaviors | LIMITED; no transfer/retention claim yet |
| TRANSFER_SUPPORTED | INDEPENDENT plus ≥1 independent held-out transfer success from a separate family with all required behaviors | DIVERSE within this skill/domain |
| RETENTION_SUPPORTED | TRANSFER_SUPPORTED plus independent delayed retention pass ≥7 days after the last relevant instruction/solution reveal; no intervening same-behavior rehearsal within those 7 days | DIVERSE within tested scope; not permanent mastery |

For one independent success without enough diversity, band remains DEVELOPING and reasons report
"one independent solve; more evidence needed". A high diagnostic is provisional, not permanent
mastery. Counts and actual demonstrated behaviors are displayed alongside the band.

Freshness v1: CURRENT through 30 days after last eligible direct observation, STALE thereafter.
At STALE, retain historical band but label current proficiency uncertain and schedule a revisit;
advancement/unlocks that require current capability cannot use the stale band alone. Dates are policy
parameters pinned in each revision. Known-active-time measurement and latency are diagnostics,
not proof of intelligence or requirements to solve faster on slower hardware.

### Contradictions and regression

Compute effective eligible observations in event-time/ID order as of the requested time. Collapse
correction lineages and repeated-family positive proof. Keep all distinct deliberate failures for audit;
for repeated-failure adaptation, duplicate/replayed commands are not deliberate failures.

- A latest eligible independent failure on a previously supported behavior marks `CONTRADICTED`
  and support INSUFFICIENT for advancement, preserving the historical supported band/reasons.
  It schedules validation or prerequisite work, not deletion of past successes.
- Two consecutive eligible deliberate failed opportunities since the latest independent success
  cap the current band at DEVELOPING and trigger decomposed practice. Repeated runs of the same
  submission, harness errors, cancellations and skips do not qualify. Distinct deliberate retries
  may trigger support even within one family, but cannot create positive diversity.
- Clear contradiction only after two independent new-family passes on the affected behavior after
  the failure, including a new held-out transfer check when restoring TRANSFER_SUPPORTED or
  RETENTION_SUPPORTED. Retention status additionally requires another qualifying delayed check.
  Until then, show supported history plus current uncertainty.
- Superseded/retracted/missing positive sources recompute the current band, which may decrease or
  return UNKNOWN. Keep earlier estimate revisions and show "source/check unavailable", not a claim
  of learner regression. Content unrelated to a skill cannot lower it.

These rules produce stable same-evidence/same-version/same-as-of output. They are intentionally
simple thresholds for #212 fixtures and #218 evaluation, not a validated measurement model.

## Adaptation policy v1

Implemented by [EV-07](adaptive-quest-policy.md), including durable inputs, candidate exclusions,
learner commands and the commit-before-content start boundary. No Evolution screen is added here.

Use ordered branches, then stable candidate ordering. Inputs are explicit player preferences, current
evidence/estimates, exposure history, graph, catalog versions, last decision and `asOf`. Explain the
chosen branch and rejected candidates. An old decision remains replayable with its saved inputs;
recompute current decisions when evidence, catalog, time/cadence or preferences change.

| First matching condition | Action / difficulty / type / cadence |
| --- | --- |
| Track paused or domain declined | No recommendation; preserve history; offer resume/change direction |
| Missing/invalid graph, unsupported rubric, unavailable runner | Block affected task with specific repair/alternative action; ordinary review/curriculum stays available |
| No direct baseline evidence | Offer diagnostic or an explicitly introductory PRACTICE task; skill remains unknown |
| Two consecutive eligible failures on target behavior | Decompose to prerequisite at lower difficulty, at most one suggested same-session retry; offer rest/change task |
| Latest eligible failure, not repeated | Target failed behavior with retry or lower-complexity variant; do not push difficulty up |
| Latest success assisted/revealed | Offer learning explanation/prerequisite then a different family at same/lower difficulty, independent validation if learner chooses |
| Contradiction/stale estimate | Independent validation at last supported difficulty or easier by choice; no harder unlock from historical band alone |
| Qualifying retention due and task eligible | Delayed check; if recent rehearsal invalidates retention, explain and defer to ≥7 days after rehearsal |
| INDEPENDENT but no transfer proof | Reserved held-out transfer at comparable difficulty with changed context; do not leak a solution first |
| Only one independent family success | Independent validation from different family at same difficulty |
| Current retention/transfer support, prerequisites met | Increase one difficulty dimension (e.g. thread count or invariant complexity), or learner-selected next skill |
| Catalog depleted / all candidates deferred or exposed | No new unseen claim. Explain depletion; offer labeled repeat, existing curriculum, wait or change goal |

Within a branch, filter by role, skill behavior, prerequisite status and learner budget/preferences;
then order by earliest due time (when present), curated difficulty, stable challenge ID/version/variant.
Dependencies with no current independent support lead to prerequisite practice, not a claim of being
incapable. Prerequisites are recommendations and safe catalog constraints, not coercive locks on study;
learners may choose a practice challenge outside the recommendation, explicitly labeled exploratory.

Defer removes the task until learner-chosen date; decline hides it until re-enabled; change direction
updates a goal/track only. Decisions record the choice without negative proficiency evidence.
Prompted explanations, review cards, repeated tasks and assisted solutions are valid learning tools;
policy simply cannot label them independent transfer.

## Engagement, milestones and unlocks (#217)

Implemented reward policy v1 preserves existing XP/level/streak totals and the existing 100 XP per
level. Migration 20 adds a local entitlement ledger and an opt-out preference, and records evidence and
submission sequence boundaries so existing observations or interrupted pre-upgrade submissions cannot
trigger reward backfills. Migration 21 snapshots already recorded mastery milestones so adding evidence
after upgrade cannot present an already-reached milestone as new. Keep rank titles visibly scoped to
engagement. New Evolution rewards use explicit entitlements:

- Award 10 effort XP once per quest objective when its first deliberate submission receives an
  attributable completed evaluation (pass, fail or partial). Tool errors, empty submissions, duplicate
  commands, cancellation and backfill award nothing. No minimum speed or hidden timer threshold.
- Award 25 milestone XP once per skill definition for first current INDEPENDENT support, and a
  distinct 25 once for first RETENTION_SUPPORTED support. Corrections never reaward; policy changes
  are prospective unless a separately reviewed migration explicitly changes entitlements. Resetting
  estimates does not reset reward entitlement history. These are motivation amounts, not proficiency.
- Key entitlements by local learner + objective/milestone identity + definition version, with policy
  version as metadata, not an extra opportunity to claim the same reward. Quest variants/retries in
  one objective do not mint new effort awards. Record source evidence/estimate revision and reason.
- Cosmetic/narrative achievements use one-time milestone records. Recommendations for harder work
  require current supported prerequisites, not an XP level. Learning content remains available as
  exploratory practice by learner choice; no paywall or compulsory lock. A stale/contradicted estimate
  pauses capability-based recommendation eligibility, not already earned cosmetics or XP.

Evaluation finalization stores the canonical result and evidence without writing XP. Afterward, the
Player System reconciles at most 32 pending prospective effort entitlements per refresh from finalized,
uncorrected evaluation records, advancing a persisted bounded scan over ineligible records; a reward
failure leaves the source finalized and the entitlement absent, so refresh/restart can retry it.
Recovery records terminal per-submission scan decisions rather than treating a submission-ID
high-watermark as proof that older unfinished work cannot later finalize. Migration 23 retires the
earlier high-watermark cursor. Thus an earlier PREPARED, RUNNING or EVALUATED submission remains
discoverable after a newer submission is reconciled. The UI
shows pending work and offers retry. Overview history is bounded and the journal retains paged audit
access. Entitlement insertion and
the existing `user_progress` XP/level balance update remain one transaction. Milestones are reconciled
only after the current Player recommendation commits, using a separate evidence snapshot fence; direct
estimate calls and replayed decisions do not award them. Their entitlement writes and balance changes
are atomic. Entitlement keys exclude policy version, so changing policy cannot re-award the same
identity. If a check is later invalidated, its proof remains auditable and earned XP is not silently
deducted. Learners can disable new rewards while continuing all learning; the first suppressed
entitlement is recorded as opted out so reenabling rewards does not retroactively mint it. Existing
DailyQuest recovery behavior is preserved, but Evolution never imposes a missed-day penalty or forced
catch-up objective. Existing legacy multi-write reward paths are unchanged and are not claimed to be
crash-safe.

## States, failure and recovery

Submission lifecycle: PREPARED → RUNNING → FINALIZED; RUNNING may become INTERRUPTED on
restart. Pause applies before submission; cancellation stops execution. Terminal evaluation results
can be viewed/retried but never overwritten. A deliberate retry gets a new command and exposure
history. Command retry after a DB error uses the same ID and source snapshot.

| Condition / failure state | Required behavior and invariant |
| --- | --- |
| Fresh install, skipped diagnostic, insufficient samples | UNKNOWN/LIMITED with reasons; no invented baseline or forced account |
| Wrong answer / partial skill demonstration | Persist failed check and any genuinely demonstrated behavior separately; choose targeted next action |
| Hint, reference answer or declared prior familiarity | Preserve provenance; no independent/unseen promotion; offer fresh-family validation |
| Repeated failure | Decompose/support/change direction; no XP deduction, shame message or endless forced retry |
| Runner unavailable / incompatible JDK / harness defect | Typed unavailable status and repair guidance; no failed skill observation |
| Artifact timeout/crash | Keep diagnostics; distinguish rubric failure from infrastructure uncertainty |
| Cancel, skip, pause, abandon, domain change | No failed ability or reward; save state/choice; expose resume. Existing ABANDONED semantics remain intact. |
| Crash before snapshot commit | No submission; draft/session remain available |
| Crash after PREPARED/RUNNING, before finalization | Mark interrupted on restart; no result inferred. Resume/re-evaluate same frozen artifact under same command only with explicit retry |
| Crash during final transaction | All writes rollback or all persist; retry same command returns committed result |
| Crash after evaluation commit, before estimate/decision | Show saved result and pending derivation; idempotently rebuild from committed evidence |
| Crash or failure applying an Evolution reward | Evaluation/evidence remain finalized; bounded reconciliation retries an absent entitlement, and entitlement + UserProgress commit once |
| Stale, contradictory, removed or corrected evidence | Show uncertainty/rebuild reason; preserve earlier decisions and original source history |
| Graph cycle/orphan, depleted held-out pool, budget too short | Honest no-candidate reason and alternatives; never manufacture content or unseen status |
| Database full/busy, artifact write error | Visible unsaved/pending status; preserve frozen command if available; rollback and bounded retry, no reward |
| Learner resets experimental estimates | New estimate epoch, source evidence/exposure retained, active reset scope explained |

The journal should answer: "What did I submit? What was observed? Which skill did that check measure?
Why did my estimate change or remain uncertain? Why this next task? What did I choose?" Raw private
source is opened locally through its original record when available, not copied into journal messages.
