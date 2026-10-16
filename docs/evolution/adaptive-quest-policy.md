# EV-07: explainable adaptive next actions

Implemented for [#215](https://github.com/MrH3lmy/CodeFit/issues/215), under
[epic #208](https://github.com/MrH3lmy/CodeFit/issues/208), from merged #231 at
`e9d05e457372c5de011169572fab50f5ceedb2ec` (migration 17).

This is a deterministic local policy interpreting existing estimates. It does not estimate ability,
grade code, award XP, expose an unseen task merely by suggesting it, or claim learning gains.

## Ownership and data flow

| Authoritative owner | Reused boundary |
| --- | --- |
| `SkillCatalogRepository` / `SkillGraph` | Exact definitions and validated prerequisite DAG; no latest-version substitution |
| `EvidenceRepository`, `DurableEvaluationRepository` | Effective corrections, aliases, actual checks and frozen assistance/exposure; accessed by the existing mastery reader |
| `MasteryEstimateService`, `OrdinalMasteryPolicy`, `EstimateRevisionRepository` | All proficiency interpretations remain `ordinal-v1`; adaptation consumes immutable revision IDs, input digests, behavior coverage and eligibility |
| `QuestCatalogRepository` | Published quest/challenge/rubric/asset versions and backing sources; adaptation publishes no content |
| `ChallengeExposureRepository` | Durable family/variant familiarity, assistance and reveal; suggestions/acceptances do not modify it |
| `BaselineDiagnosticService` | Existing skip choices and stable baseline presentation identities; no new baseline results or grader |
| `ChallengePresentationService` | Exposure and existing session/draft creation before content; a caller-owned overload returns only the receipt ID for atomic start authorization |
| `ChallengeEvaluationCoordinator` | Original submission/evaluation/evidence transaction; committed results remain valid if adaptation subsequently fails |
| `ProblemLibraryService.getNextRecommendedProblem()` | Sole selector for ordinary new curriculum work; no Evolution reordering or roadmap memberships |
| `AdaptationDecisionRepository` | Immutable decision inputs and explicit learner choices, used directly by the future journal projection |

Production code is in [the adaptation package](../../src/main/java/com/codefit/evolution/adaptation/).
`AdaptiveQuestPolicy` is pure Java: no clocks, database connections, UI, network, runner, AI, XP or
randomness. The installed adapter supplies versioned metadata, not source or hidden test content.
`AdaptiveQuestService` is an explicit on-demand application boundary. It starts no background worker.

V1 goal selection is an exact target `SkillRef`, or the default foundational goal. Selected track is
EVOLUTION or CURRICULUM. Other learning features remain accessible through their existing services.
With no explicit goal, the first prerequisite-ordered skill lacking current support is selected.
The initial default budget is 20 active minutes; published Java exercises estimate 30–50 minutes,
so a learner must explicitly choose a suitable budget before those become eligible. Budgets do not
imply speed-based ability judgments or modify running timers.

## Ordered policy and candidate eligibility

Policy **`adaptive-v1`**, configuration **`java-pilot-v1`** consumes `ordinal-v1`. The following table
is implemented in order. Branch selection never changes a saved estimate.

| First matching case | Action and limits |
| --- | --- |
| Paused track | No task or exploratory suggestion. Offer resume/change direction/ordinary review. |
| Unsupported exact graph/estimate context | Explicit unavailable decision; no fallback to another graph, rubric or policy. Unavailable candidate assets/runner are filtered individually. |
| UNKNOWN baseline | Prefer an eligible BASELINE task, otherwise explicitly introductory PRACTICE at difficulty ≤2. Diagnostics do not require proving their own prerequisites. Skips are respected. |
| Two consecutive eligible deliberate failures on a behavior | Decompose toward an unsupported prerequisite or a lower/same-difficulty practice family. A family with two prior submission commands is not forced again; rest/change direction and explicit exploration remain available. |
| Latest eligible observation is a single genuine failure | Retry or a fresh easier/same-difficulty family for the failed behaviors; no harder task. |
| Latest success assisted, revealed, or independence unknown | Consolidation in a different family at same/lower difficulty; no independent advancement inferred. |
| Contradicted, stale, or historically high but not currently supported estimate | Fresh-family validation at last observed difficulty or easier; historical band cannot unlock harder tasks. |
| Current transfer support and an actually eligible delayed check is due | Reserved RETENTION with exact curated equivalence, full required behavior coverage, unfamiliar family and at least seven days of recorded quiet time. |
| Current INDEPENDENT without transfer support | Eligible reserved TRANSFER from a different, unfamiliar, equivalent context. Comparable curated difficulty is bounded to one ordinal step beyond the last observed task. |
| DEVELOPING with insufficient coverage/diversity | Another fresh independent PRACTICE family at same/lower difficulty. Familiar repeats cannot add diversity. |
| Current transfer/retention support | A prerequisite-supported next skill or at most one ordinal difficulty step. A future/depleted retention task does not block this branch. Waiting is permitted; relevant rehearsal postpones the delayed check. |
| No candidate satisfies the selected branch | Explicit depleted/unavailable result with recorded exclusions and permissible alternatives. Never fabricate a challenge or weaken an eligibility filter. |

The policy uses only the estimator's eligible deliberate-failure and independent-proof metadata.
Unavailable/unsupported/compile/runtime/cancellation outcomes excluded by the estimator do not become
capability failures. One command is one correlated opportunity. Identical duplicate metadata collapses;
conflicting metadata for the same submission rejects. Consecutive relevant failures reset on an
independent successful opportunity, as in the approved foundation. An assisted retry cannot erase a
contradiction. Behavior-specific failure counts never come from raw check counts or command replays.

Every candidate must satisfy its exact graph, selected behavior coverage, availability, budget,
learner preferences, retry bounds, cohort role and difficulty bound. Ordinary practice recommendations
require currently supported published prerequisites. A new graph/version is unsupported until an
explicit reviewed adapter exists. Invalid cyclic/incomplete prerequisite contexts fail closed.
PILOT_POST is always reserved; BASELINE, TRANSFER and RETENTION never become exploratory practice.
Only explicitly selected PRACTICE may bypass *recommendation* prerequisites or novelty restrictions,
and it is labeled `EXPLORATORY`. Availability, exact identity, budget and learner choices still apply.

Novelty requires no prior family presentations/submission commands, hint, solution reveal or known
familiarity. This is eligibility to *offer* an unfamiliar opportunity, not an assertion of proctored
independence: the presentation and evaluator still capture the learner's actual declaration and aid.
Another variant/version never resets family history. Decline/defer also suppress related installed
variants in the same family, preventing an immediate renamed retry. Reversal addresses the original
choice. Exposed hold-outs never reappear as unseen transfer/retention.

Difficulty ceilings use actual independently supported task metadata when available, bounded further
by the last observed task for consolidation/retry/validation. A familiar or assisted harder success
cannot raise the advancement ceiling. With no independent proof, consolidation stays at or below the
observed task; only current supported capability permits an ordinal step.

Stable ranking within the selected branch uses the declared role (baseline before introduction;
lower/fresh support before a forced retry), applicable due time, curated difficulty, challenge ID,
version, variant, then quest identity. There are no weighted/random scores. Every candidate retains
its exclusion reason codes, eligibility, exploratory eligibility, rank and applicable UTC due time.
An unavailable candidate does not relax the requirements for another candidate.

Retention due time comes from the existing mastery input's relevant activities, including presentations,
hints/reveals, prepared or completed submissions, and raw mapped legacy rehearsal even before ingestion.
Unknown relevant timestamps prevent a ready claim. The maximum relevant activity plus seven days is
the due time; an exact boundary qualifies. Known future activity cannot make a check ready. Adaptation
never infers that missing records prove no practice outside the app. Recommendation alone does not
interrupt the quiet period.

## Consistent snapshot and durable decisions

`recommend(graph, asOf)` performs:

1. On one connection, capture SQLite `data_version`, start a read transaction, and collect preferences,
   all four exact skill inputs, catalog/binding/asset availability, exposure and submission metadata.
   `MasteryEstimateService.capturePilot` reuses its existing reader on that same connection. Each skill
   therefore belongs to the same database snapshot; the older `pilot()` convenience API is unchanged.
2. Release the transaction. Prepare estimates through the existing estimator, including canonical input
   encoding. For CURRICULUM, call the existing no-argument selector exactly; concurrent changes are
   caught by the same version validation. Do not create or re-rank ordinary candidates.
3. Acquire a short writer lock, check the original `data_version` on this same connection, and publish
   the prepared exact estimate revisions. If another connection changed data, retry the snapshot.
4. Release the writer. Assemble adaptation input with those revision IDs, run the pure policy and
   encode the canonical frozen input outside the transaction.
5. Acquire the writer again and validate `data_version` before publishing the immutable decision.
   A correction, mapping change, exposure, reset or learner choice in between requires a fresh read.
   Earlier harmless derived estimate revisions may remain; they cannot authorize a stale decision.

Three unsuccessful attempts return an explicit pending/retry error. Database failures never become
learning failures. No transaction spans Java compilation/execution. Recommendation is never invoked
inside evaluator finalization; the caller derives it only after the evaluation commits. A failure in
this service cannot roll that evaluation back and creates no evidence.

Migration **18** adds two local append-only tables, immutable update/delete guards and a history index:

- `evolution_adaptation_decisions`: stable SHA-derived decision ID, policy/configuration, exact graph,
  preferences version, UTC as-of, canonical V1 metadata input/checksum and frozen output/checksum.
- `evolution_adaptation_choices`: unique command ID, payload/checksum, optional source decision FK,
  explicit chosen UTC instant, graph and resulting versioned preference snapshot/checksum.

The saved input contains exact candidate/rubric/manifest digests and eligibility facts, assistance and
exposure summaries, relevant evidence/submission/check references, exact estimate revision IDs/input
digests/epochs/policy, goal/track/budget, declines/deferrals and UTC as-of. The chosen action, exclusions, rankings, alternatives and explanations are also stored in a bounded
canonical result. Replaying the pinned pure V1 interpreter must match that saved result exactly. These
outputs are not recomputed from today's database. Unknown serialization/policy/configuration versions
and digest/identity corruption fail closed. No speculative V2 policy registry is introduced (#232).

Identical input/policy/configuration/as-of produces the same decision ID and no additional revision,
choice, observation or presentation. A changed as-of is meaningful because due dates/freshness may
change. Historical source withdrawal does not rewrite an earlier decision. New historical-as-of
requests consume current effective corrections, as described by [mastery estimation](mastery-estimation.md);
they are not a reconstruction of what was known before a correction. Exposure remains conservatively
known across resets and historical queries.

Input collection and serialization are bounded: ≤128 skills, ≤256 candidate identities, ≤10,000 rows
per history collection and ≤32 MiB per encoded snapshot, with stable keyset pages from existing owners.
Over-limit state returns an explicit error rather than a partial advancement. Preferences are replayed
on demand with a finite 10,000-choice bound. Decision/choice journal reads use sequence keysets and
limits 1–128. There is no continuous startup history scan, general event store or copied private artifact.

## Learner controls and presentation API

```java
var adaptation = new AdaptiveQuestService();
var decision = adaptation.recommend(Instant.now());
// Show primary/reasons/rankings; do not render challenge content from catalog repositories.
var task = decision.result().primary();
if (task != null && task.challenge() != null) {
    var response = new LearnerCommand("accept-01", CommandKind.ACCEPT,
            decision.id(), task.challenge(), null, null, null, null);
    adaptation.respond(response); // idempotent; DOES NOT expose a challenge
    var view = adaptation.start("accept-01", Familiarity.DECLARED_UNFAMILIAR);
    // Only now render view.prompt(), publicChecks(), draft(). Exposure has committed.
}
// After a deliberate submission is committed by the existing evaluator:
// evaluation.submit(existingSubmissionRequest);
// adaptation.recommend(Instant.now()); // independent, safely retryable derivation
```

`LearnerCommand` also supports DECLINE, DEFER(until UTC instant), RESUME_CANDIDATE, PAUSE, RESUME,
CHANGE_GOAL(exact skill or null for foundational default), CHANGE_BUDGET(1–180 minutes), SWITCH_TRACK,
CHOOSE_ALTERNATIVE and EXPLORE. A UI accepting a calendar date must resolve its intended timezone into
an explicit instant before calling this API; the policy uses no machine-local dates.

`respond` returns the saved choice. Reusing an ID with another payload rejects; replay after restart or
a lost response returns the original snapshot without applying it to current preferences again. Commands
cannot move backward behind the latest recorded learner-choice clock. Defer must choose a future instant;
at that exact instant the candidate may become eligible again if all other conditions still hold.
Decline persists until explicit reversal, with no penalty. Epoch reset does not erase choices or exposures.

Accept/alternative/exploration commands validate the original decision and current eligibility under the
writer lock. Accepting ordinary curriculum uses ACCEPT with a null challenge; its saved decision pins
the original problem ID and current selection must still match. The existing navigation opens it.

Starting a newly selected Evolution task revalidates current state and commits exposure with authorization
in one writer transaction through `ChallengePresentationService.present(Connection, Request)`. A choice
cannot bypass a later pause, decline, changed goal, missing asset or already exposed hold-out. Stable
presentation IDs allow restart/lost-response recovery. Existing presentations may be resumed without
claiming restored novelty; reusing the presentation identity with a different declaration conflicts.
Hints, solutions and submissions continue through their existing services, never through adaptation.

## Privacy, compatibility and remaining limits

Migration 18 does not rewrite source history, XP, drafts, curriculum, estimates, assessment scheduling,
published assets or sharing contracts. Unavailable future graph decisions deliberately keep their
requested reference even when unpublished; this is a limitation record, not a catalog authorization.
All controls, decisions and frozen metadata stay local and are absent from peer aggregate sources and
signed payloads. No P2P field, message, consent scope or network service is added.

The finite pack limits automatic progression: reproduction has only one independent repair-practice
family, and liveness only one practice family. Correct prerequisite checks may therefore return no
primary advanced task even when a downstream skill has two passing families. Explicit labeled practice
remains available; this implementation does not fabricate prerequisite support, add content, or change
published V1 thresholds. Catalog difficulty is a curated ordinal, not a calibrated metric; consolidation
cannot silently move from a difficulty-2 counter to a difficulty-3 invariant exercise just because it is
available. Optional exploration can choose it explicitly with an honest label and adequate budget.

The evaluator still supports only its reviewed mechanisms, is not a security sandbox, and cannot prove
absence of outside help or universal correctness. No effectiveness claim is made. Existing deferred
#221/#222/#224/#225/#228/#229/#230/#232/#233 remain unchanged. New pack versions/domain adapters need
explicit review; this PR does not create hypothetical future engines. Next implement **#216**, consuming
these UI-ready services and journal projections without opening reserved content through generic routes.

See [verification and adversarial review](adaptive-quest-verification.md) for exact tests and limits.
