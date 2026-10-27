# Gate 2: acknowledged draft to durable evaluation

Baseline: `8000617df12e38da39024abe3b1f240613557117`. Disposable opt-in Linux practice profiles only.

## Side-effect audit (before implementation)

- `PracticeDraftService`: caller-owned short SQLite writer transactions; authorizes committed desktop presentations, exact bundled PRACTICE Java content and backing sessions; reads bounded existing source and notes. Profile-wide monotonic trigger revision detects changes. Saves reuse `PlayerSystemService.save(Connection,Draft)`.
- `ChallengeEvaluationCoordinator.prepare`: freezes source, reflection, assistance, presentation exposure, exact manifests, assembled artifact and execution configuration; publishes the exact evidence mapping and PREPARED row. Existing API owns its transaction, so reading a draft then calling it is **not atomic**. Add a caller-owned transaction overload; do not duplicate assembly or snapshot storage.
- `resume`: existing global JavaExecutionCoordinator, PID/start identity, fencing token, three-interruption budget, bounded compiler/workloads, immutable EVALUATED report and atomic FINALIZED transaction. Execution happens outside SQLite transactions. Existing Java mechanism verifier is a restricted supported-form verifier, not a host sandbox.
- Finalization: existing LOCAL_EVALUATION ProblemAttempt, immutable evaluation revision, canonical evidence observation and aliases. `ProgressionService.markOptedOutSubmission` retains its existing opt-out bookkeeping/entitlement behavior. No new reward reconciliation, PlayerSystem overview, mastery publication or adaptive policy call is permitted here.
- `cancel`: persists cancellation only before EVALUATED and cancels the active process token. It does not rewrite an already staged/finalized result. Cancellation is not a second durable stage.
- `PlayerSystemService.submit`: presentation validation, optional baseline prepare then coordinator resume. This slice excludes baseline and calls the same coordinator directly to obtain atomic draft freezing; it does not call overview or its reward/adaptation reconciliation.
- `DurableEvaluationRepository`: authoritative immutable source/report checks. Only coordinator View (check IDs, status, redacted feedback) may cross IPC, never snapshots or private diagnostics.
- Desktop main owns profile/session, journals metadata and fences stale frames. Existing JavaFX ownership lock and experimental marker remain required; source learner snapshots remain read-only. No real-profile schema migration.

## Transaction and intent design

Main generates a durable submission command ID before transport. Its journal holds only presentation, acknowledged revision and assistance declaration. Java checks the revision and freezes current bounded source/reflection **inside the same transaction** as coordinator PREPARED and an immutable experimental command-to-revision receipt. No source bytes enter the journal.

If a crash occurs before commit, neither receipt nor PREPARED exists. Explicit reconciliation reports absence; it never reconstructs from the latest draft or automatically replaces the command. A new deliberate confirmation is required. If committed, status/resume always uses the original immutable artifact, regardless of subsequent edits. Replaying a committed prepare validates immutable revision/declaration metadata instead of re-reading today's draft.

Java executes only after explicit resume. A single worker runs the existing coordinator while the protocol thread remains available for status/cancel. No background polling, queue or parallel submission state machine. A second unfinished submission is rejected. Every status requires the live session and matching committed presentation/desktop submission receipt. Recent discovery is bounded and does not execute anything.

The implemented contracts, verification and release restrictions follow.

## Exact application and IPC contracts

Protocol version remains 1; commands are finite additions shipped together with this sidecar. Every frame has an exact UUID transport `id`, `profileId`, `sessionId`, and `v:1`. Owning-frame authorization precedes input parsing in main; Java checks its live profile/session before dispatch. No renderer-supplied process parameters or filesystem paths exist.

| Java method | Additional request fields | Successful payload |
| --- | --- | --- |
| `practice.submission.prepare` | main-owned UUID `commandId`, `presentationId`, integer `expectedRevision`, `assistance` | `evaluation` receipt |
| `practice.submission.status` | `commandId`, `presentationId` | receipt or `null` (no PREPARED commit) |
| `practice.submission.resume` | same | observed receipt, with `active:true` while Java work is dispatched |
| `practice.submission.cancel` | same | observed receipt; cancellation is a request, not rollback |
| `practice.submissions` | `presentationId` | latest 32 desktop-owned receipts for that presentation |

Renderer capabilities: `preparePracticeSubmission(input,profileId,sessionId)`, `practiceSubmissionAction({action:status|resume|cancel,commandId,presentationId},profileId,sessionId)`, `listPracticeSubmissions(presentationId,profileId,sessionId)`. The three exact channels are `codefit:practice:submission-prepare`, `codefit:practice:submission-action`, and `codefit:practice:submissions`. This finite action enum does not dispatch arbitrary Java methods.

A receipt contains command/presentation IDs, acknowledged draft revision, assistance declaration (`UNKNOWN`, `NONE_DECLARED`, `DECLARED`), exact challenge ID/version, UTC submitted timestamp, frozen artifact SHA-256, ephemeral worker-active flag, cancellation-request flag and the existing coordinator View: durable stage, nullable outcome, withdrawal flag and bounded check ID/status/redacted feedback. It contains **no** source, reflection, harness, expected outputs, hidden inputs, answer keys, private diagnostics or evidence payload. Newline JSON frames retain the 512 KiB limit and 15-second command deadline. Execution is asynchronous within Java and therefore not constrained to one transport request's deadline; the existing frozen runner profile and per-check execution bounds remain authoritative.

`PracticeDraftService.authorized` restricts presentations to a committed desktop Start with exact bundled/installed PRACTICE Java definitions, backing problem and existing solving session. History/status also verify immutable snapshot identity against that presentation and challenge. Missing context, artifacts, inconsistent receipts or oversized private blobs fail closed. Revision values remain nonnegative safe JavaScript integers below 9,007,199,254,740,000. Zod strict response schemas enforce every allowlisted field, enum, collection and string bound, and reject inconsistent outcome/stage combinations. Transport correlation includes command, presentation, revision and assistance, as applicable.

## Persistence and recovery

`PracticeSubmissionSchema` installs only after experimental marker, ownership lock, core schema/integrity and draft metadata validation. It adds `desktop_submission_commands(command_id,presentation_id,draft_revision,assistance)` with foreign keys and immutable UPDATE/DELETE triggers. Exact DDL is checked on repeated startup. There is no new core migration, draft-content table, evaluation table or real-profile upgrade.

Preparation acquires the existing SQLite writer lock, validates authorization and draft revision, calls `ChallengeEvaluationCoordinator.prepare(Connection,SubmissionRequest)`, and inserts the desktop correlation receipt in one FULL-synchronous commit. The overload is the existing prepare body, not another freezer. Its legacy API retains the original transaction and fault boundary for JavaFX. An induced receipt-insert failure proves mapping/snapshot rollback. No write transaction spans learner execution.

- Before PREPARED commit: explicit status returns absence. The main journal is cleared only after that check; no automatic replacement command or mutable-draft reconstruction occurs. The learner must reconfirm the current acknowledged revision.
- After PREPARED: the same immutable snapshot is used after draft edits, lost responses and restart. A repeated prepare checks the original revision/declaration receipt rather than today's draft. Conflicting payload under an existing ID is rejected.
- RUNNING: existing coordinator fencing, process identity, orphan handling and three-execution recovery budget are unchanged. One worker per owner; another unfinished submission is refused. Resume while active returns its status without enqueuing execution.
- EVALUATED: the existing staged report is finalized without execution. FINALIZED: the durable result returns without another attempt, evidence root or revision.
- Cancel: PREPARED/RUNNING records the request and uses the existing token; the explicit action can finalize a prepared cancellation through the coordinator. Already EVALUATED/FINALIZED results are preserved. No false claim that cancelling undid a commit.
- Shutdown: cancel the active operation and await worker termination. Before releasing the profile lock, the dedicated main process checks termination; a worker that cannot stop causes process halt while ownership is still held. Hard-crash recovery remains the existing coordinator's responsibility, not a new process-recovery subsystem.

The metadata-only main journal stores no source/reflection. A successful receipt clears it; if the renderer loses that reply, bounded Java submission history rediscovers the command. If main loses the Java reply, the persisted intent blocks another prepare until explicit status reconciliation. UI changes do not poll or re-execute automatically. Embedded editor history loads only through explicit Refresh submission status, avoiding background IPC competition with restored draft operations; the separate read-only inspector can load immediately.

## React experience and privacy

The editor enables Submit only for acknowledged, unchanged content with no save/recovery pending. Confirmation pins the displayed revision and captures explicit assistance; subsequent edits invalidate that confirmation. Preparation and execution are separate deliberate actions. Frozen results display challenge/version, submission, presentation, revision, timestamp, artifact digest, durable stage/outcome, cancellation and per-check feedback. Editing afterwards neither replaces the frozen source nor rewrites the saved result.

A separate “Inspect saved submissions” route works after cold restart without loading a draft or revealing challenge instructions. It can resume/cancel the original frozen submission with the same Java authorization boundary. Failed history refresh clears stale results. Existing dirty-navigation protections and private learner snapshot projections remain intact; no draft skill IDs enter execution, mastery or recommendation state.

All source and reports remain in the owner-only local experimental SQLite file. There is no new dependency, telemetry, P2P field, network endpoint or unrestricted renderer capability. Source/reflection/diagnostics are excluded from the operation journal and error messages. Existing JavaFX continues to use its original coordinator and drafts; read-only Mission Console and Curriculum Preview remain distinct.

## Adversarial findings and residual limits

Confirmed gaps corrected: automatic history reads competing with existing draft operations (caught by unchanged stale-editor and force-quit regressions); non-atomic read-then-prepare; missing durable revision correlation; pending-intent ambiguity before PREPARED; profile-lock release before an evaluation worker finished; result access coupled to editable draft loading; cross-presentation pending recovery selection; stale history on failed refresh; prototype CSS collision; FINALIZED briefly retaining an active-worker flag during worker teardown (reproduced by the hard-kill/replay test).

Residual limits: Linux disposable profiles only. Existing engine is **not a security sandbox**; no downloaded or peer-supplied code is executed by this workflow. Only supported Java forms/rubrics can earn verified check success. Unavailable compiler and unsupported forms are honest non-success outcomes. Full real-profile write safety, cross-platform execution (#228), existing hard-crash orphan-registration gaps (#229) and additional correct mechanism support (#230) remain separate reviews. Cooperative profile locks do not protect against malicious same-user processes or older binaries deliberately pointed at the private profile. Plaintext local artifacts retain the existing permissions and no destructive retention policy is introduced. Saved-history discovery is bounded to 32 per presentation. Corrupt/missing exact context is unavailable rather than silently repaired.

## Verification and file map

Production changes: coordinator caller-owned prepare/status overloads and same-connection repository lookup; experimental receipt schema and Java adapter/protocol/lifecycle; strict TypeScript contracts, main-owned submission journal/IPC; PracticeEvaluation component and existing editor/workspace integration. Tests cover actual SQLite finalization, rollback, revision conflicts, distinct concurrent commands, immutable artifacts after edits, staged/final replay, unavailable tools, withdrawal, corrupted blobs, all 4 unreviewed skills/16 stages, transport loss and renderer recovery. Existing evaluator crash/fence/timeout tests run unchanged.

Commands: `DISPLAY=:112 mvn -B test`; `JAVA_HOME=<Java21> CODEFIT_MAVEN=<Maven> npm run prepare:java`; `npm run build`; `JAVA_HOME=<Java21> npm test`; `npm run package`; `DISPLAY=:112 JAVA_HOME=<Java21> CODEFIT_DESKTOP_EXECUTABLE=<packaged binary> sh scripts/offline-linux.sh npm run test:e2e`; `npm audit --json`; `git diff --check`.

Initial complete verification passed 1,966 Java tests and 79 Node tests before final review additions. Final counts and commit-specific CI links are maintained in the PR. All five focused packaged evaluation tests pass, including restart without an editor, cancellation, lost response and exact finalized SQLite counts. All four screenshot compositions were visually inspected. No assertions or isolation were weakened. Synthetic incomplete Java deliberately produces COMPILE_ERROR; this is tested separately from real packaged reference solutions that pass the existing evaluator. The dependency audit retains the baseline's one low esbuild development-server advisory, zero moderate/high/critical; manifests and lockfiles are unchanged.

Actual packaged screenshots (synthetic source, redacted feedback, protected instructions collapsed):

- [1280 dark](screenshots/evaluation-1280-dark.png)
- [1280 light](screenshots/evaluation-1280-light.png)
- [760 dark](screenshots/evaluation-760-dark.png)
- [760 light](screenshots/evaluation-760-light.png)

Next review: integrate the existing Java-authoritative learning overview after finalized submissions, with separately audited reward/recommendation reconciliation and explicit presentation. No frontend mastery inference, new syllabus grading, real-profile writes, Gate 3 or premature closure of #261/#268 is implied.
