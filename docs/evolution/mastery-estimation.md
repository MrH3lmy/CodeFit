# EV-04: baseline diagnostics and conservative skill estimates

Implemented for [#212](https://github.com/MrH3lmy/CodeFit/issues/212), under
[#208](https://github.com/MrH3lmy/CodeFit/issues/208), from main
`99015651de526653040627ca98f435bbd831c955` (merged #227, migration 16).

An estimate interprets available evidence at an explicit UTC event-time cutoff. It is neither a reward
nor a permanent judgment about a learner. No percentages, XP inputs, AI grading, network services,
adaptive selection or new JavaFX screens are introduced.

## Existing ownership and integration audit

| Existing implementation | Established facts and reuse | Boundary preserved |
| --- | --- | --- |
| `SkillCatalogRepository`, `SkillGraph`, `JavaDebuggingSkillSeed` | Immutable exact graph/definition versions; validated prerequisite DAG; four pilot skills each have one composite observable behavior | Graph membership/prerequisites never imply learner proficiency. No graph-version selection policy is added. |
| `EvidenceRepository.forSkill(Connection,...)`, `EvidenceMappingRepository` | Paged interpretations, current source validation, correction heads, retractions, aliases and superseded mappings | The estimator consumes these views; it does not ingest, regrade, repair or rewrite evidence. |
| `DurableEvaluationRepository.evaluationInput(Connection,...)` | Exact canonical observation, rubric check kinds/targets/statuses and catalog refs | The metadata projection now also supplies the frozen receipt, submission-time exposure and prior family submission count. No artifact/answer/hidden case is returned. |
| `ChallengeEvaluationCoordinator` | Frozen deliberate commands, immutable evaluations, canonical evidence and existing source attempt aliases | Baseline submissions and interrupted-command resume delegate directly. Execution guarantees remain those of #214. |
| `ChallengePresentationService`, `ChallengeExposureRepository` | Exposure before content, durable family history, hints/reveals, existing Java session/draft | Baseline uses the same service and stable receipt; skips never manufacture attempts. |
| `QuestCatalogRepository`, packaged Java training pack | Exact reserved BASELINE definitions and immutable cohort/family identities | No pack v1 manifest, asset, Problem binding, curriculum membership or hold-out route is changed. |
| `AssessmentAttemptService`, `AssessmentGradingService`, weekly controller | Existing #104 item validators and subjective Java/concept grading | Add prospective grading/presentation/assistance facts atomically with new source writes; historical booleans remain legacy. Rotation/selection and source attempt storage remain unchanged. |
| `MasteryService`, `ProgressService`, legacy dashboards/readiness | Card recall thresholds, engagement XP/levels and existing summaries | Unchanged; none is an input or consumer of the new capability estimates. |
| P2P snapshots, ordinary attempt queries, signed publication | #214 excludes reserved Evolution attempts before aggregate computation | No new field, source query, sharing scope, consent or network message. Estimates, resets and baseline preferences stay local. |

## Contracts and V1 decision table

The [mastery package](../../src/main/java/com/codefit/evolution/mastery/) owns immutable
`EstimateInput`, `MasteryEstimate`, `OrdinalMasteryPolicy`, revision storage and application services.
The pure policy has no database, clock, runner, JavaFX, reward or network dependency. Its version is
`ordinal-v1`; the thresholds are exactly 2 independent families, 7 days of retention delay and 30 days
of freshness. Changing policy semantics requires a new version and retaining the old interpreter.

| Band | V1 requirement | Support |
| --- | --- | --- |
| UNKNOWN | No adequate direct, available, timed machine-observed skill performance | INSUFFICIENT |
| DEVELOPING | Relevant checked failure, partial, assisted success, diagnostic-only result, or insufficient independent diversity/coverage | LIMITED; failure does not mean zero ability |
| INDEPENDENT | Two distinct-family independent passes, including at least one outside BASELINE, cumulatively covering every required behavior | LIMITED |
| TRANSFER_SUPPORTED | INDEPENDENT, then a separate genuinely unfamiliar reserved TRANSFER family with complete behavior coverage and curated equivalent changed context | DIVERSE within checked scope |
| RETENTION_SUPPORTED | TRANSFER_SUPPORTED, then an independent equivalent RETENTION family after at least seven days without recorded relevant exposure, instruction or rehearsal | DIVERSE within checked scope |

An eligible non-baseline observation can support INDEPENDENT without being relabeled as practice.
A transfer cannot simultaneously supply its own prerequisite: TRANSFER_SUPPORTED requires two other
qualifying families observed before that transfer presentation. A transfer presented before sufficient
foundation cannot later become an independent transfer validation by accumulating practice.
The initial curated equivalence plan pins `inventory-transfer/v1` and `queue-retention/v1` to their
exact graph/target skills from the approved Java pilot plan. Cohort or a new ID alone never establishes
equivalence. Other domains require an explicit reviewed context/equivalence provider, not Java-specific
branches in the ordinal policy. PILOT_POST keeps its cohort and never becomes an unseen-transfer result merely by supporting the
underlying independently checked skill.

`MasteryEstimate` includes exact graph/skill and epoch, current and historically supported bands,
support/freshness, contradiction and regression-cap flags, advancement eligibility, independent family
count, independent behavior coverage, unique effective observation counts by provenance/assistance/
exposure, last meaningful event time, as-of, policy version and structured reasons. Contributions name
the exact evidence/mapping, submission, family, passed/failed behavior indices, relevant required check
IDs, deliberate-failure eligibility and independent-proof eligibility. Exclusions name the evidence,
mapping and reason. These are UI-safe facts for #215/#216; private artifacts remain with #214.

### Eligibility and correlation

Independent positive proof requires all of:

- Current, available, nonretracted LOCAL_CHECK evidence whose exact observation matches the durable
  evaluator input on the same read connection. A mapping targets an exact skill, but its behavioral
  scope comes from actual required rubric checks, not the mapping/category name.
- PASS with every required check passed; for a behavior, every relevant required check must pass.
  Optional stress passes and ungraded reflection cannot fill a missing required behavior. A trusted
  optional check that actually observes a mapped counterexample can contradict prior support; optional
  status is not permission to ignore negative observations. A keyed
  diagnostic is useful direct diagnostic progress, but cannot establish independent engineering repair.
- `NONE_OBSERVED` assistance: no observed in-app aid plus an explicit absence-of-outside-help declaration.
  UNKNOWN assistance, “no in-app hint recorded,” hints, outside assistance or solution reveal cannot
  establish independence. The declaration is not proctoring.
- Explicit declared unfamiliarity, no prior family/variant exposure or submissions, no known familiarity,
  hint or solution reveal, and exactly this one presentation in the frozen family context. FIRST_PRESENTATION
  alone only means first recorded locally. REPEAT/RELATED_VARIANT cannot add independent positive diversity.
- One canonical source root/submission/attempt alias and one positive unit per family. A check count,
  variant, replay, correction or new mapping version never creates another solve.

SELF_REPORTED and LEGACY_UNSPECIFIED results cannot establish independence. OBJECTIVE_REVIEW retains
its item-validation scope and likewise cannot establish the engineering behavior. Unavailable outcomes
(including unsupported valid forms, compilation/runtime/environment errors, timeout and cancellation)
are not learner failures. Relevant observed required-check failures are needed for a negative inference.
Unknown event times are excluded; ingestion time is never substituted. Future observations do not
change event-time bands/counts/freshness. Ties use event time, then stable evidence ID and exact mapping.

### Contradiction, freshness and reset

A new eligible unaided deliberate failure on a previously independently supported behavior marks
contradiction and support INSUFFICIENT for advancement. One failure preserves the supported band;
two consecutive eligible deliberate failures since the latest independent success cap current status at
DEVELOPING. Replaying the command is not another failure. Related-family deliberate failures can count
for support needs even though related positive retries cannot create diversity.

Clearing contradiction requires two new independent families after the failure, each covering the
affected behavior. Restoring prior transfer support also requires a new held-out transfer; restoring
retention additionally requires a fresh qualifying delayed check after that transfer. One fresh pass
cannot clear a previous two-failure cap. Earlier successful evidence remains visible.

CURRENT includes the exact 30-day boundary after the last eligible direct observation; STALE starts
one nanosecond later. The band can remain historical, but `currentCapabilitySupported` is false when
stale or contradicted. With no timed direct observation, freshness is `NO_OBSERVATION`, not a fabricated
date. Skips, unavailable checks and uncertain legacy records do not refresh direct capability evidence.
Recent assisted/familiar progress may be a current direct observation, but cannot alone refresh old
independent proof for advancement; `currentCapabilitySupported` then remains false with an explicit
independent-validation-due reason.

Reset creates a graph-scoped named epoch at the actual UTC reset time with a canonical source-sequence
watermark. Previously published source roots remain excluded even after correction/remapping or a skewed
event clock. Events at the exact reset timestamp are conservatively excluded as ambiguous, along with
events before it. They cannot support the active estimate. Receipt history, assistance, original evidence, evaluations, source attempts
and prior revisions survive. A command replay returns the original reset. Backward reset clock movement
is rejected rather than bringing older proof back into scope. Pre-reset rehearsal still affects retention.

Retention uses the last relevant recorded activity before presentation, not just the last success.
Presentations (conservatively including skipped/exposed content), hints/solutions, prepared/interrupted
submissions, and mapped recall/assessment history interrupt the quiet interval. Relevant persisted legacy
activity is checked through `LearningEvidenceSourceRepository` even when bounded ingestion has not caught
up. Unknown rehearsal timestamps prevent a retention claim. Rehearsal between delayed presentation and
submission also invalidates it. No claim of observing outside-app instruction or practice is made.

## Consistent snapshots, revisions and historical interpretation

`MasteryEstimateService` performs explicit on-demand derivation:

1. Open one connection, capture SQLite `data_version`, and begin one read transaction.
2. Load exact graph, active epoch at as-of, explicit mapping versions, evidence pages, evaluator metadata
   and relevant exposure/rehearsal on that same connection. Evidence uses ordered sequence cursors/pages
   of 128; submission/exposure activity uses numeric keysets; mapping versions remain explicit.
3. Commit/release the read transaction before policy computation and canonical metadata encoding.
4. Return an identical saved revision without another write when the input is unchanged. Otherwise
   acquire the short writer lock and compare `data_version` **on that same connection** before insertion.
   A concurrent correction, mapping transition, reset or source change retries from a fresh snapshot.
5. Publish one immutable input snapshot keyed by policy + input SHA-256. Three failed snapshot retries
   return a visible pending/retry error; they never publish a partially mixed estimate.

There is no startup scan/worker. Pages and snapshots are bounded (10,000 input records per collection,
32 MiB encoded input); over-limit input returns an error, never a partial positive estimate. Explicit
requests may page an entire relevant history; this is not continuous background rescanning. Unrelated
concurrent database writes can conservatively cause retries. Each returned per-skill revision identifies
its own coherent snapshot; a four-skill pilot report is not claimed to be one atomic graph-wide revision.

The bounded binary input stores metadata, exact skill definition, policy inputs, evidence/mapping refs,
check summaries and activity times, with checksum and canonical decode validation. No source/answer/key/
hidden test payload is duplicated. `find(revision)` and paged `history(...)` reproduce prior results from
those exact frozen inputs, even if current sources are later withdrawn. Input digests and policy identity
prevent duplicate revisions for identical evidence, epoch and as-of. A new as-of is a meaningful distinct
interpretation because freshness can change. Unknown interpreter versions fail closed. Missing mappings, superseded selected mappings, and known
interpretation gaps in an explicitly selected replacement block current advancement with INSUFFICIENT
support; they never fall back to older positive interpretations.

**Historical meaning:** a new `estimate(..., asOf)` uses *currently effective corrected evidence*, filtered
by source event time. Known withdrawn/broken results are not resurrected by asking for an earlier date.
To inspect what CodeFit concluded before a later withdrawal, inspect the saved prior revision. Existing
legacy correction records do not reliably record authentic historical knowledge time; this implementation
does not invent a bitemporal reconstruction from ingestion time. Future learner performance is excluded;
current source invalidation changes eligibility without claiming learner regression.

## Baseline application boundary

```java
var baseline = new BaselineDiagnosticService();
var status = baseline.status(Instant.now()); // 3 eligible diagnostics; 4 per-skill estimates
var task = new CatalogRef("baseline-counter", 1);
var view = baseline.present(task, QuestTypes.Familiarity.DECLARED_UNFAMILIAR);
// Only now render view.prompt(), publicChecks() and the existing draft.
var result = baseline.submit(task, new SubmissionRequest(
    "deliberate-command-1", view.receipt().id(), editedSource,
    EvaluationTypes.OutsideAssistance.NONE_DECLARED, reflection));
// Retry with the SAME command, or resumeSubmission(command); never silently change the artifact.
baseline.skipTask(task, "skip-command-1");
baseline.skipAll("skip-track-command-1");
// Explicit resumeAll / resumeTask preserve the existing receipt and completion history.
```

`resumePresentation(task)` returns an existing exposed view without reconstructing its original declaration.
Paused tasks accept replays of existing commands but block new submission commands until explicit resume.
Stable presentation identity is derived from the exact graph/challenge; restart/re-entry returns the
same receipt. Changing familiarity under the same request conflicts; later known familiarity can be
recorded through the existing presentation service. Task/track choice commands are append-only and
idempotent. A skip before presentation does not disclose content. Skipping an already presented task
also records the existing exposure skip event atomically with the preference. Results remain in the
original submission store, named by command; `submissionStatus` and `resumeSubmission` return the
existing redacted evaluator view. No second baseline attempt store, grading path or workspace exists.

`Status.nextActions` provides per-skill need codes/reasons (unknown baseline, independent validation,
stale validation, contradiction, or supported checked scope). This is input eligibility for #215, not
an adaptive quest choice. The installed baseline covers keyed reproduction/diagnosis and atomic repair;
it does not manufacture invariant/liveness diagnostics. Those skills stay UNKNOWN until direct evidence
exists. The initial pack has finite family coverage; notably keyed reproduce diagnostics do not give a
second code-repair family, and liveness has only one authored practice family. These limitations must
remain visible; no v1 content or threshold was weakened to award a higher band.

## Prospective #104 assessment facts

The weekly controller records the actual item/variant, its new presentation identity/time, hint use,
and whether reference comparison preceded subjective self-rating. `recordGradedAttempt` atomically
writes the existing assessment attempt and `assessment_attempt_facts`; failure rolls both back.
The metadata includes grading kind, actual validator name, exact variant ID/index/content digest,
submission UTC, prior item attempt count and exposure (REPEAT or UNKNOWN). Existing validators have no
published rubric version, so `gradingVersion` remains null. No historical fact is reconstructed.

Supported exact/SQL/regex validation has OBJECTIVE_REVIEW scope, not LOCAL_CHECK. Subjective CONCEPT
and JAVA_CODE remain SELF_REPORTED. SQL environment/configuration errors and regex timeouts/configuration
failures retain unavailable grading metadata even though the old weekly boolean/report behavior remains
compatible. Legacy `recordAttempt` and all historical rows without facts remain LEGACY_UNSPECIFIED.
The source adapter reads additive facts only when present. No assessment schedule/card interval is touched,
and reserved Evolution assessments are rejected by this ordinary prospective writer.

## Migration, privacy and risks

Migration **17** creates four append-only local tables: `evolution_estimate_epochs`,
`evolution_estimate_revisions`, `evolution_baseline_choices`, `assessment_attempt_facts`, plus the revision
history index and immutable update/delete guards. Exact graph/skill/source foreign keys protect ownership.
It performs no history scan, remap, reset, XP update or old-row rewrite. Repeated initialization is inert.
Historical migration tests remove only later additive tables in isolated fixtures and retain original row
preservation assertions. Real database downgrade/destructive deletion is not implemented.

New tables/services are absent from every P2P source/projection. The existing signed day/week/timezone
and preparation-payload integration test now includes real evaluation/correction/withdrawal, estimate
revision/reset and baseline choice writes, asserting byte-identical peer payloads and ordinary totals.
No estimate or rationale is shared without a future explicit contract.

Remaining limits: local declarations cannot prove outside independence; installed held-out assets are
inspectable; the Java evaluator remains a bounded local runner, not a secure sandbox; curated equivalent
contexts and ordinal thresholds are not psychometric validation; current-effective historical queries
are not invented historical knowledge reconstruction; finite catalogs may not support every higher band;
bounded input limits require explicit future scale work; and immutable revisions accumulate local metadata.
No learning-gain or cross-domain claim is made. #228, #229 and #230 remain deferred and unchanged.

Next implement **#215**, using these versioned revisions, eligibility flags, failed behavior/check refs,
exclusions and reasons to select an explainable next quest. #216 owns screens/journal rendering;
#217 owns rewards. Neither legacy mastery nor XP should be connected as a shortcut to this policy.
