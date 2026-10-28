# Read-only post-evaluation learning debrief

Baseline: main `678f41bd46b8dd02dfe4372aa6fb15d1e156fd46` (#277).

## Side-effect audit (before implementation)

| Existing owner | Actual behavior | Debrief use |
| --- | --- | --- |
| PlayerSystemService.overview | Calls recommendCurrent and reconcilePendingEffortRewards; reads derived revisions and journal | Never called |
| AdaptiveQuestService.recommend/recommendCurrent | Derives/publishes estimates and immutable decisions; current additionally reconciles milestone entitlements | Never called |
| ProgressionService | Award/reconcile/setRewardsEnabled write reward history, entitlements and preferences; snapshot is a separate read | No calls, including snapshot: rewards are outside this projection |
| MasteryEstimateService.estimate | Coherent read followed by possible immutable revision publication | Never called |
| EstimateRevisionRepository | find/history reproduce saved input interpretations; save/reset write history/epochs | Reuse bounded LearnerSkillProjection historical interpreter on this read transaction; never derive/publish current estimates |
| EvidenceRepository | append writes canonical observations/aliases/mappings; find/head read immutable facts | Read only, validate against durable evaluation and effective source head |
| AdaptationDecisionRepository | save/respond persist decisions/choices; history/find are saved historical reads | No new decision. This slice labels next actions exploratory and eligibility NOT_EVALUATED |
| ChallengeEvaluationCoordinator | prepare freezes; resume executes/finalizes attempt/evidence and existing reward opt-out bookkeeping; status(Connection) returns redacted facts | Only same-connection status; never prepare/resume/cancel from a debrief |
| DurableEvaluationRepository | evaluationInput(Connection) validates exact evaluation/evidence/check mapping; find reads private immutable snapshot | Bound private records before decoding; project explicit safe fields only |
| ChallengePresentationService | present/reveal/rehearse persist exposure; contextForSubmission acquires a SQLite writer lock even for metadata; persistedContext is the new caller-owned read-snapshot variant | Same PracticeDraftService checks via persistedContext, without presenting, revealing, or loading editor |
| JavaFX Player System | Explicit result display can separately invoke overview/derivation | Unchanged; desktop does not copy its mutating overview path |

The live profile remains exclusively owned, Linux-only and disposable. Debrief uses its own `mode=ro`, `query_only=ON` connection and one coherent transaction. No schema changes. Read failure leaves the finalized evaluation accessible and never causes re-execution.

## Proposed explicit reconciliation gate (not implemented)

A later reviewed command must name the exact live profile/session, deliberate command identity, source finalized submission and intended policy/graph. Separately authorize estimate publication, adaptive decision publication and reward reconciliation, with their actual side effects and independent durable receipts. Do not wrap all three in a misleading refresh. Preserve the existing policies, input snapshot fencing, opt-out settings and entitlement idempotency. Reads must remain available when any explicit derivation fails. Review real-profile migration/backup/rollback and platform safety independently; this proposal grants no such permission.

## Implemented contract

`practice.submission.debrief` is a finite protocol-v1 request with exactly `v`, transport `id`, `method`, `profileId`, `sessionId`, `commandId` and `presentationId`. The renderer calls `readPracticeDebrief({commandId,presentationId},profileId,sessionId)` through the single owning-frame-only `codefit:practice:debrief` channel. No source, paths, graph selection, timestamps, queries or policy options are accepted. Main and Java both validate identity. Existing 15-second deadlines, 512-KiB frames, child supervision, cancellation/disconnect and generation fencing apply. No automatic reconnect or background polling.

Successful response has the ordinary identity envelope and a `debrief` v1 object:

- `readOnly:true`, authentic UTC `readAt`, `currentEligibility:NOT_EVALUATED`, `nextAction:EXPLORATORY_PRACTICE`.
- Existing redacted finalized evaluation receipt: command/presentation, frozen revision/artifact digest, outcome/check feedback, withdrawal and submission time. PREPARED/RUNNING/EVALUATED are not debriefs.
- Exact quest, challenge (in the evaluation receipt), graph and rubric IDs/versions; current immutable evaluation revision, correction flag, family, variant, cohort and frozen familiarity/reveal/prior-family facts.
- Check ID, kind, required flag, safe feedback, immutable authored limitations and exact skill/behavior targets. Targets are not claims that those behaviors passed. No I/O cases, specifications, reference assets or run diagnostics.
- Evidence status and actual effective canonical observation ID/revision/previous ID, ingestion timestamp, provenance, outcome, availability, assistance, exposure, change, limitation codes and exact mapping identity/availability, including explicit SUPERSEDED status when a newer mapping is published. It remains one correlated opportunity regardless of check count. Missing, mismatched, future-dated or superseded evidence does not fall back to an older pass.
- Exact relevant skill definitions and **saved historical** interpretation metadata: revision/as-of/policy, ordinal band, support, saved freshness, contradiction, excluded count, saved behavior coverage, reason codes and lifecycle warnings. Current support is always NOT_EVALUATED. No estimated probability, unlock, score or new mastery revision.

`PracticeDebriefProtocol` explicitly writes only these DTO fields. `practice-contract.ts` uses strict bounded Zod objects and coherence checks; the transport additionally correlates the response's outer and inner command/presentation against the pending request. Errors remain redacted.

## Coherent read and ownership

`PracticeEvaluationService.debrief` opens `ExperimentalProfile.connection(true)` (`mode=ro`, `query_only=ON`) and one transaction. It checks the existing desktop revision receipt, committed presentation, exact bundled PRACTICE Java catalog/backing and immutable submission. A bounded finalized evaluation is then projected with the existing coordinator's connection-scoped status and repository evaluationInput. Evidence facts must match that durable source and the effective evidence head; the exact persisted mapping and interpretation link are checked separately. Corrupt records fail closed. No draft content is read by the debrief.

The read-only test initially caught `contextForSubmission`'s no-op INSERT writer-lock acquisition. The new `ChallengePresentationService.persistedContext(Connection, id)` factors the same metadata read into a caller-owned snapshot. Existing preparation/save callers still use `contextForSubmission` and its lock; no authorization or writer semantics were weakened. The practice authorization checks are shared, not reimplemented with relaxed rules.

Bounds include 64 evaluation revisions/runs, 8 MiB aggregate payload per evaluation table, existing 2-MB per-artifact/report checks, 64 evidence revisions/128 KiB per observation/1 MiB aggregate evidence payload. The reused skill projection retains its 32-node/128-edge, 1-MiB per saved estimate/8-MiB aggregate and bounded historical-input checks. Over-limit data becomes unavailable rather than partial positive support. The renderer never receives these private blobs.

`LearnerSkillProjection` reproduces only existing persisted inputs with the existing ordinal policy. It does not call MasteryEstimateService, publish an estimate or reinterpret historical freshness at the new read time. Later withdrawals, corrections, mapping changes and epoch changes remain warnings. A newer graph never replaces the submitted exact graph. Saved interpretations can predate the evaluated submission and the UI says so. Missing revisions remain “Not yet evaluated.”

There is no new database, table, migration, journal, cache or write process. The exclusive disposable owner from #275–#277 remains authoritative. There are no changes to real learner snapshots, P2P aggregates, JavaFX workflows, grading, XP or curriculum review status.

## UI and recovery

The existing finalized result exposes an explicit “Read learning debrief” action. Four scoped cards show evaluation summary, persisted evidence, skill development and exploratory continuation. Authored v1 limitations are disclosed as immutable specification text (some predate the evaluator implementation), separate from current recorded results. Required/optional checks and behavior targets are keyboard-inspectable. Colors never stand alone for outcome or confidence. Dark/light responsive layouts reuse The Ascent tokens; no new animation or dependency.

The debrief is accessible from the cold-start saved-submission inspector without an editor or prompt. Reads clear the previous summary while pending or failed; component/session fences discard late responses. Failure says **“Evaluation saved. Learning summary unavailable.”** and leaves the saved evaluation and retry-read controls intact. No failure handler calls resume, prepare, cancel, save, recommendation or reconciliation. Existing navigation and dirty-draft guards still own leaving the workspace. The continuation link returns to existing practice controls; choosing another challenge still uses explicit Java authorization/exposure.

## Verification and adversarial review

Tests use isolated disposable databases. `PracticeDebriefIntegrationTest` compares the contents of **every table** before/after repeated reads, including attempts, evidence, estimates, exposure, decisions, rewards and drafts. It covers eight outcome projections, real packaged reference execution, commit-before-lost-response restart, cancellation, corrections/retractions, historical/stale/future interpretations, exact graph pinning, missing/superseded mapping links and damaged evidence. Outcome-matrix fixtures are explicitly synthetic reports finalized by the real coordinator; they test projection semantics, not new grader correctness. A separate test executes a genuine valid reference through the unchanged evaluator.

Existing `LearnerSkillProjectionTest` covers insufficient/stale/contradictory/withdrawn evidence, incompatible graphs, missing tables, malformed/oversized revisions and immutable source reads. The debrief reuses that exact Java implementation. Node tests exercise the real private Java process, schema limits/private-field rejection, wrong identities, lost response/disconnect fencing and restart with the original evidence. Packaged Electron tests cover failed reads without re-execution, editor-free restart, strict owning-frame/argument boundaries, late replies after disconnect, visible keyboard scope inspection and screenshots. Existing full regressions include JavaFX, P2P, exposure, drafts, execution, Ascension Tree and Curriculum Preview.

Review outcomes: no current support inferred from a saved band, no per-check independent solves, no older evidence fallback after invalidation, no policy service invoked by refresh, no hidden content serialized, no draft stage accepted as a submission ID, no extra profile writer, no new dependencies. The strict read-only connection caught and drove removal of writer-lock acquisition from this read path. The UI preserves the original rubric's historical caveat rather than silently editing its published wording.

Commands: `DISPLAY=:114 mvn -B test`; `JAVA_HOME=<Java21> npm test`; `npm run build`; `JAVA_HOME=<Java21> npm run prepare:java`; `npm run package`; `DISPLAY=:114 JAVA_HOME=<Java21> CODEFIT_DESKTOP_EXECUTABLE=<packaged binary> sh scripts/offline-linux.sh npm run test:e2e`; `npm audit --json`; `git diff --check`. Final counts and exact CI links are recorded in the PR.

Screenshots use an actual packaged app, disposable synthetic source and redacted COMPILE_ERROR feedback, with protected instructions collapsed. They are not evidence of learning gains or independent curriculum review:

- [1280 dark](screenshots/debrief-1280-dark.png)
- [1280 light](screenshots/debrief-1280-light.png)
- [760 dark](screenshots/debrief-760-dark.png)
- [760 light](screenshots/debrief-760-light.png)

## Remaining limits

Linux opt-in disposable profiles only; no real-profile writes or Windows/macOS support. The local evaluator remains **not a security sandbox**. Existing #228–#230 limitations remain. Persisted private artifacts remain plaintext behind existing owner-only permissions; no new retention policy. Only the installed exact pilot graph has the approved historical interpreter. This slice does not publish estimates, display current recommendation eligibility, reconcile rewards or establish independent content approval. Unsupported/missing interpretations remain explicit. All #253/#269 author drafts and review gates are unchanged. Manual assistive-technology and native platform validation remain separate.

The next separately reviewed slice is the explicit reconciliation gate above, followed by consuming its durable Java receipts. Keep #261/#268 open; do not claim full Gate 2 or start Gate 3.

## #279 — Preserve captured debriefs on unchanged status refresh

`PracticeDebrief` previously reset on the entire evaluation object's reference. Every status reply constructs a new object, so an unchanged finalized result erased its captured summary. The parent now keys the component by profile, live session, command, presentation, Java-owned effective evaluation revision, withdrawal, stage and outcome. Unchanged status retains the component and its authentic `readAt`; changed identity remounts it immediately and unmount cleanup fences pending reads.

The existing evaluation receipt now explicitly projects `evaluationRevision`: the durable repository's `final_revision`, or `null` before finalization. Java reads it in the same transaction as the effective outcome; no schema, persistence, authorization, evaluator or policy change. Strict bundled Java/TypeScript contracts require this metadata, and debrief validation requires its revision to match the outer projection. This detects corrections even when the outcome is unchanged. Older incompatible sidecars fail strict validation rather than guessing a revision.

A preserved summary is explicitly a **captured learning record**, not live support. Evidence, mapping and interpretation changes can occur independently; only an explicit debrief refresh re-reads them and changes the capture time. There is no polling, automatic reconciliation or promise that a normal status read refreshes evidence.

Regression verification includes the reproduced packaged baseline failure, unchanged status/list refresh, explicit timestamp refresh, switching actual finalized submissions, correction/withdrawal invalidation, pending-response fencing and disconnect/reconnect. Packaged correction responses are controlled IPC fixtures, while Java integration tests verify actual durable revisions (including repeated same-outcome correction and withdrawal). Exact profile/session identity also has a contract-level test. Full-suite counts and final CI links are recorded in the follow-up PR.
