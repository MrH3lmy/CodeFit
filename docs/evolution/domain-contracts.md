# Domain model and V1 contracts

**Proposed, not implemented.** #210–#217 own the additions identified below. Names are frozen at
the semantic boundary; Java records may be introduced only when their owner ticket needs them.
Do not add empty engine classes in #209. [ADR](adr-0001-local-evolution.md) owns storage/privacy decisions.

## Fundamental concepts

| Concept | Definition / minimum data | Owner |
| --- | --- | --- |
| Player | Local learner view: selected goal/skill IDs, domain, budget, selected track, paused/deferred tasks; composed engagement/estimate views | #212 baseline preferences; #216 UI; existing UserProgress for engagement |
| Skill | Stable namespaced ID (`java.concurrency.atomic-update`), domain, definition version, observable behaviors, lifecycle ACTIVE/RETIRED | #210 |
| Skill Graph | Versioned directed prerequisite edges with exact skill-version endpoints; deterministic traversal | #210 |
| Evidence | A traceable observation mapped to one or more exact skills, with quality/assistance/exposure and correction status | #211 |
| EvolutionQuest | A meaningful development objective with challenge candidates, rationale, effort budget and optional milestone; distinct from existing DailyQuest | #213 definitions; #215 recommendation |
| Challenge | Versioned content and rubric, expected skill/check mapping, family/variant, assets, constraints and exposure role; one attemptable task | #213 |
| Submission | Stable command plus immutable artifact reference/digest for one deliberate submission; links one existing ProblemAttempt on finalization | #214 |
| Evaluation | Durable observations from exact checks and rubric on a submission; includes negative, partial and unavailable outcomes | #214 |
| MasteryEstimate | Rebuildable, evidence-backed ordinal skill estimate with confidence/recency/contradictions and reasons; not an immutable fact about a human | #212 |
| AdaptationDecision | Versioned choice of next development action with eligibility/exclusion reasons, input references and learner response | #215 |
| Progression | Auditable effort/milestone reward entitlements, levels and unlocks; independent of estimates | #217 |
| EvolutionJournalEntry | Projection of source/evaluation/evidence/estimate/decision/choice/reward records ordered by recorded time and ID | #216 presentation; storage remains with each owner |

A skill definition includes a task-independent behavior: e.g. "repairs a shared-state compound update
so all controlled interleavings preserve the count, and distinguishes visibility from atomicity."
"Concurrency", "adaptable" or "smart" alone is not a measurable criterion. Broad descriptors may
summarize explicitly named observations; no general cognitive score is inferred in V1.

Graph edges mean prerequisite → dependent. Reject missing endpoints, cycles, self-edges and duplicate
edges; use stable ID ordering in topological traversal. A retired skill remains addressable in history.
A new graph version never changes existing evidence's skill definition or fabricates prerequisites met.

## Evidence structure and identity

An `EvidenceObservation` has:

- `evidenceId`, `contractVersion`, `sourceRef(type, localId, sourceRevision, eventKind)` and optional
  `evaluationId`; no source answer/code copy. Types include REVIEW, ASSESSMENT_ATTEMPT,
  PROBLEM_ATTEMPT and CHALLENGE_EVALUATION.
- `observedAt`, `recordedAt`, `timeBasis` and optional legacy original timestamp/zone assumption.
  New observations use UTC `Instant`; elapsed active time uses measured duration, not wall-clock subtraction.
- `provenance`, `outcome`, `assistance`, `exposure`, `qualityLimitations`, source availability,
  source/content digest when supported, evaluator/rubric/config versions.
- Targets `(skillId, skillDefinitionVersion, behavior/checkIds, mappingVersion)`. A single submission
  may support several distinct behaviors, but cannot count twice for one skill because two adapters
  saw it (runner + ProblemAttempt). For evaluated challenges, evaluation is the authoritative observation;
  its linked attempt contributes activity only.
- `supersedesEvidenceId` / `retractsEvidenceId`, correction reason and recorded actor (system/learner).
  Corrections must point to the same source lineage and cannot form cycles.

`eventKey = sourceType + sourceId + sourceRevision + eventKind` uses a canonical length-delimited
encoding (not ambiguous string concatenation). A source revision identifies a correction/regrade,
not a new solve. Unique `(eventKey, mappingVersion)` prevents adapter replay; effective evidence
selection collapses all revisions/mappings to one active interpretation of an observation per skill.
The same source key with conflicting payload is an error requiring inspection, not last-write-wins.
Backfill cursors order source IDs and commit with inserted evidence. Source IDs are local, never peer IDs.

Historical ProblemAttempts lack per-attempt hints; use assistance UNKNOWN, not current
`problem_progress.solved_with`. New evaluations capture assistance before reset; exposure history
is retained across sessions, retries and estimate resets. Resetting estimates cannot make a seen task unseen.

### Quality dimensions stay separate

| Dimension | V1 values and interpretation |
| --- | --- |
| Provenance | LOCAL_CHECK: machine-observed exact rubric checks; OBJECTIVE_REVIEW: existing explicit supported validator; SELF_REPORTED: manual judge result/subjective rating; LEGACY_UNSPECIFIED: missing grading information; AUTHENTICATED_EXTERNAL: reserved, unsupported in V1 (no authenticated judge ingestion exists). Imported manual AC stays SELF_REPORTED with imported origin. |
| Outcome | PASS, FAIL, PARTIAL, UNAVAILABLE; evaluation execution statuses below refine these. No non-answer/skip encoded as a failed capability. |
| Assistance | NONE_OBSERVED, HINT(level), EXPLANATION_OR_SOLUTION, DECLARED_EXTERNAL, UNKNOWN. `NONE_OBSERVED` means no observed in-app aid plus learner declaration; it never proves no outside aid. All disclosed aid is allowed. |
| Exposure | FIRST_PRESENTATION, REPEAT, RELATED_VARIANT, HELD_OUT_TRANSFER, DELAYED_RETENTION, UNKNOWN; also family/cohort IDs, prior presentations, prior solution reveal and learner familiarity. First presentation is not automatically transfer. |
| Availability | AVAILABLE, SOURCE_UNAVAILABLE, RETRACTED, UNSUPPORTED_VERSION. Preserve failure/missing distinction. |

Legacy assessment booleans have no durable hint/grade provenance: record LEGACY_UNSPECIFIED and
UNKNOWN assistance/exposure, even if today's item type suggests a validator. New assessment source
writes in #212 must capture grade kind, hints/reveal, exact variant/rubric identity and presentation,
using additive metadata with legacy-safe defaults. #211 only adapts what exists; it does not invent it.

## Challenge, rubric and evaluation

A challenge manifest pins `challengeId/version`, `domainId`, `familyId`, `variantId`, role/cohort,
target behaviors/skill versions, prerequisites, difficulty ordinal with domain meaning, estimated active
effort, retry/hint rules, constraints, asset digest, author/license/source, runner type and `rubricVersion`.
Rubric lists every check ID, kind, required/optional status, target behaviors, expected result,
limits, deterministic schedule/seed and feedback. Time/complexity expectations must be explicit;
wall-clock speed alone is not a portable quality measure.

A `SubmissionContext` pins the backing `problemId`, command ID, challenge refs, artifact digest/ref,
assistance/exposure snapshot, presentation ID, creation/submission UTC times and lifecycle state.
The mutable Java draft is copied once into a local immutable submission artifact; retries of the same
command use it, not whatever the editor now contains. Persist local artifact path/digest and bounded
compiler/test diagnostics. Never store executable artifacts in the journal or a wire message.

An `Evaluation` includes submission/evaluation revision IDs, JDK/OS description, manifest/rubric/
evaluator versions, run bounds, per-check status/output digest/bounded diagnostics, overall status,
measured active duration where known and correction lineage. Overall statuses are:

| Status | Meaning | Evidence impact |
| --- | --- | --- |
| PASSED | All required checks pass, no required result unavailable | Positive only for checked behaviors; assistance/exposure still constrain inference. |
| FAILED | Required check observed a mismatch/assertion failure | Negative for targeted checked behavior, with actual failing check references. |
| PARTIAL | Some mapped behaviors demonstrated, overall requirement not met | Per-skill observations only; never label overall quest passed. |
| COMPILE_ERROR | Submitted artifact does not compile | Feedback; negative evidence only for a rubric behavior that compilation can measure. |
| RUNTIME_ERROR | Learner artifact crashes/nonzero exit | Negative only where attributable to intended task, not harness/toolchain defects. |
| TIMED_OUT | Artifact exceeds a specified bound | Negative if the published rubric tests termination/complexity and environment is valid; otherwise inconclusive. |
| CANCELLED / INTERRUPTED | User cancellation / restart while running | No negative proficiency inference or reward. |
| TOOL_UNAVAILABLE / HARNESS_ERROR / UNSUPPORTED | Evaluation could not reliably run | UNAVAILABLE, preserve diagnostics; no skill penalty. |

A zero process exit is not by itself PASSED. Missing/truncated output needed to judge a check is
UNAVAILABLE, not an implicit pass. Optional stress failures can refute a robustness claim; absence
of stress failures does not prove all interleavings. Regrading requires a pinned new revision and explicit
supersession; the old result remains readable and contributes no second sample.

Existing `SubmissionResult` stores external-style verdicts and cannot express this status set.
#214 must add a narrowly defined `LOCAL_EVALUATION` value (or equivalent non-judge marker) for
new original-challenge ProblemAttempts, with enum/read-path regression tests. It must **not** write
AC/WA to imply an external verdict. Evaluation is authoritative for local result; `ProblemProgress`
may summarize completion through the Evolution coordinator, without using the legacy external
`finish()` defaults. Existing external attempts and accuracy rules stay unchanged. The marker must
be excluded from existing external-judge denominators as well as peer aggregates.

## Proposed application contracts

The following signatures are design sketches, not compiled declarations or authorization to scaffold
all interfaces. Values named above supply their fields; immutable collections and explicit results are
required. Implement pure methods where a single implementation suffices.

```java
interface SkillCatalog {
    SkillGraph load(GraphRef version); // validated DAG; unsupported returns a typed error
}
interface EvidenceAdapter {
    EvidenceBatch readAfter(SourceCursor cursor, int limit); // no source mutation
}
interface EvidenceStore {
    AppendResult append(Connection tx, EvidenceObservation observation); // inserted / duplicate / conflict
    EvidenceSet effective(SkillRef skill, EstimateEpoch epoch, Instant asOf);
}
interface ChallengeEvaluator {
    EvaluationResult evaluate(SubmissionSnapshot submission, ChallengeSpec spec,
                              RunCancellationToken cancellation); // no DB writes
}
interface SkillEstimatePolicy {
    MasteryEstimate estimate(SkillDefinition skill, EvidenceSet effective,
                             EstimatePolicyVersion policy, Instant asOf);
}
interface AdaptationPolicy {
    NextQuestDecision decide(PlayerContext player, CandidateSet candidates,
                             EstimateSet estimates, EvidenceSet effective,
                             DecisionPolicyVersion policy, Instant asOf);
}
interface EvolutionCompletion {
    CompletionResult finalizeSubmission(SubmissionId id, EvaluationResult result);
    // owns one transaction; replays return existing result; conflicts never award
}
interface ProgressionPolicy {
    List<RewardEntitlement> rewards(CompletedObservation observation, RewardPolicyVersion policy);
}
```

Policy inputs include versions and data snapshots, not live services/repositories, randomness or
system time. Candidate lists are stably sorted. If variety requires randomness later, persist its seed;
V1 needs none. `NextQuestDecision` may contain no quest plus a typed reason, never an invented ID.
Explanations are structured reason codes plus evidence IDs and user-readable text, not model prose.

## Events and transaction ownership

These are local persisted fact types, not a distributed event bus:

| Event | Producer / required identity | Consumer |
| --- | --- | --- |
| ChallengePresented | #213/#216, presentation ID + challenge/family/cohort + versions + time | Exposure eligibility; written before rendering, including skipped items |
| SubmissionPrepared | #214, stable command ID + source digest + assistance snapshot | Execution/recovery |
| EvaluationRecorded | #214, evaluation ID + submission ID + status/checks + versions | Evidence, journal |
| SkillEvidenceRecorded / EvidenceSuperseded / EvidenceRetracted | #211, source event key + target refs + correction links | Estimate rebuild |
| MasteryEstimateRevised | #212, revision/epoch + input digest/IDs + policy + as-of | Adaptation, journal |
| NextQuestDecided | #215, decision ID + inputs/candidate exclusions + policy/config + as-of | UI, journal |
| QuestPaused / Deferred / Declined / DirectionChanged | #216, choice ID + decision/quest ref + requested preference | Eligibility; no negative evidence |
| RewardApplied | #217, unique entitlement + delta + policy/source ref | UserProgress, journal |

Evaluation finalization atomically stores the existing attempt reference, evaluation and evidence;
source legacy adapters use idempotent catch-up. Estimation/selection may follow after commit as
rebuildable work. If they fail, UI shows "evaluation saved; next recommendation pending" and retries
from persisted inputs. No rollback of real evidence because a recommendation is unavailable.
Reward ledger insertion and UserProgress update are one transaction in #217. No source adapter,
policy, UI controller or peer callback may independently mutate estimate or award balances.

## Invariants

1. One learner submission command produces at most one source attempt and authoritative evaluation
   revision; regrade/mapping changes are corrections, not new observations.
2. Evidence may be negative; mastery estimates may decrease or become uncertain. Unknown data
   cannot be substituted with zero, FAIL, independent PASS or a fabricated timestamp.
3. XP/streak/quest completion count never enters skill estimation. Positive local evidence always
   names the behaviors/checks and exact artifact/rubric it supports.
4. Multiple checks of one artifact and repeat/family variants are correlated evidence. They do not
   become multiple independent solves. Hints/reveal cannot be erased by starting a new session.
5. No source schedule/history is rewritten by evidence ingestion, estimation or adaptation. Imported
   curriculum introduction uses the existing frontier; quests do not create roadmap memberships.
6. Definitions are immutable per version; retired/unsupported definitions remain historical references.
   Broken graph/catalog data blocks affected recommendations with reasons, not existing study.
7. Pause/skip/direction changes are preferences, not failed challenges. Inactivity reduces freshness,
   not XP or personal worth. Users can inspect reasons, override direction and reset estimates.
8. Only local learner observations affect personal estimates. No implicit peer sharing or AI grading.
