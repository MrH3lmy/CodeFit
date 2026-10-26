# Authorized experimental practice — lifecycle audit

Baseline: `0e88019dda895861976b57f8849c3a846882ed74`. Open PR #255 is the
superseded JavaFX styling branch; no changes are taken from it. Scope is original
published Java Training Pack PRACTICE only. #261 and #268 remain open.

## Side effects and authority (before implementation)

| Owner | Contract and consequences |
| --- | --- |
| ChallengePresentationService.present(Connection, Request) | Validates exact assets/binding and creates a presentation, solving session and starter draft in the caller transaction. Returns only an identity. Does not enforce adaptive eligibility. |
| ChallengeExposureRepository.append | Validates exact quest/challenge/cohort, retry limits and familiarity; same presentation ID and identical input replays, conflicting input rejects. |
| ChallengePresentationService.resume | Resolves persisted presentation and validated assets; returns prompt/draft/public checks. Must only run after commit. Does not establish eligibility for a new presentation. |
| AdaptiveQuestService.respond/start | Current JavaFX choice path validates current policy; start commits exposure then resumes. Derivation also publishes mastery estimates and recommendation records. Not suitable as a side-effect-free listing operation. |
| AdaptiveQuestPolicy | Exploratory PRACTICE uses base exclusions (availability, graph, budget, decline/defer and retry), not prerequisite mastery. The ordinary recommendation path additionally evaluates skill support. Reuse the exploratory rule, do not infer a recommendation or unlock. |
| PlayerSystemService.overview | Derives recommendations and reconciles rewards; excluded. |
| ChallengeEvaluationCoordinator | Durable submission, evaluator, evidence and reward lifecycle; excluded. |
| JavaSolutionDraftRepository | Presentation necessarily creates the initial Java draft. No editing/saving/submitting API in this slice. |
| ExperimentalProfile / ProfileLock | Dedicated marked profile only; lock before bootstrap, exact schema 25 and integrity checks on reopen; no migration of existing databases. JavaFX acquires the same cooperative lock before initialization. |
| DesktopApplication / OperationJournal | Main persists immutable intent before transport; Java atomic receipts; lost replies require reconciliation using the same command. Extend this pattern without storing challenge bodies in a journal. |
| LearnerSnapshot / LearnerMissionProjection | Frozen query-only readers, never authorization or a writer connection. Remain unchanged. |

## Planned boundary

Use a separate Linux-only, explicitly opted-in disposable experimental profile,
with a distinct marker and host-selected private directory. Never accept a renderer
path or real-profile identity. A per-process live session identity fences requests;
command identity survives session restart. Acquire the existing cooperative lock
before any SQLite write. Older JavaFX binaries cannot be assumed to honor this
lock: this profile is not an imported/shared JavaFX profile and must never be
manually opened by an older binary. Arbitrary programs with the same OS account
are outside cooperative ownership guarantees. Real-profile writers stay disabled.

Expose only current exploratory practice candidates from the existing Java policy.
Factor its existing exploratory exclusion predicate for reuse; do not calculate
mastery, publish estimates/recommendations or invoke reward reconciliation.
Revalidate exact installed graph/catalog, preferences and retry policy under the
presentation transaction. Commit exposure and the immutable command binding before
returning instructions. Initial sessions/drafts are indispensable existing
presentation effects, not a new workspace store.

Cancellation means stop transport and reconcile, not undo exposure. A lost reply
never authorizes a new command ID. Durable status contains identity only; restricted
instructions are returned solely by explicit authorized open/resume. No hints,
solutions, private checks, evaluator artifacts or protected bodies in logs/journals.
All draft syllabus IDs remain outside the installed PRACTICE allowlist.

Implementation, exact DTOs, tests, screenshots and residual findings will be
recorded here as they are verified. This audit is not a claim of implementation
or platform readiness.

## Implemented transaction and recovery design

`PracticeApplicationMain` owns one marked `experimental-practice-v1` directory,
chosen by Electron main, with directory mode 0700 and database/marker mode 0600.
It checks Java 21, Linux, actual parent PID, cooperative ownership, SQLite integrity,
foreign keys and exact schema 25. New profiles use the existing atomic staging
bootstrap. Existing profiles are verified, never passed to the schema initializer.
The new private `desktop_practice_commands` table belongs only to this disposable
profile format; there is no learner-schema migration. Immutable rows contain only
command and exact quest/challenge references, never prompts or source.

1. Main durably writes `pending-practice.json` (0600, atomic rename and directory
   sync) before sending a command. One unresolved intent prevents another Start.
2. Java validates live profile/session and the exact bundled PRACTICE allowlist.
3. Java freezes the command references durably. Conflicting reuse fails even if
   an earlier eligibility/exposure transaction was interrupted.
4. Under the existing catalog writer lock, Java rechecks installed catalog,
   current exploratory eligibility, exposure and retry policy; existing
   presentation/session/starter-draft writes commit together.
5. Only then does `ChallengePresentationService.resume` read the permitted prompt.
   No hint, solution, harness, draft or evaluator output enters this API.
6. A status lookup returns receipt metadata only. Explicit recovery first checks
   that status, then opens using the original immutable command. An existing
   exposure resumes without counting again; absent exposure is reauthorized.
7. Acknowledgement clears only main's pending intent after validating a durable
   receipt. It never deletes the Java command or exposure. A later deliberate
   Start is a new exposure, not an independent solve or completion claim.

Cancellation/timeout closes the transport and terminates the owned process. It
cannot undo a commit. Recovery is required for ambiguous completion. The parent
watchdog terminates an orphaned Java process; the OS releases its lock and SQLite
recovers an unfinished transaction. No polling/rescanning service is introduced.

## Exact IPC contract

Separate practice protocol v1; unrelated snapshot protocol and deck protocol are
unchanged. `PracticeProtocol` and `practice-contract.ts` are the executable schemas.
Every request has `v:1`, UUID request `id`, `profileId`, live `sessionId`, and one of:

| Method | Additional fields | Result |
| --- | --- | --- |
| `practice.list` | none | At most 32 candidate references, published quest title, expected effort |
| `practice.status` | UUID `commandId` | Command ID and nullable durable receipt; no instructions |
| `practice.open` | UUID `commandId`, `questId`, `questVersion`, `challengeId`, `challengeVersion` | Durable receipt and authorized instructions only after commit |

Receipts pin command, presentation, quest/challenge versions and UTC presentation
time. Successful replies repeat method and profile/session identities. Main checks
request, method, command and exact receipt correlation. Strict schemas reject extra
fields; Java rejects duplicate keys, non-flat JSON, malformed UTF-8 and wrong types.
Requests are at most 4 KiB, responses 64 KiB, instructions 24,000 characters, and
process operations have a 15-second deadline. Error codes contain no exceptions,
paths or content. Bounded stderr is drained without retaining or forwarding it.

Preload exposes finite connect/open/recover/acknowledge/disconnect capabilities.
Only the owning top-level frame can invoke their exact argument counts. Renderer
paths, SQL, shell commands and URLs are absent. Main generates command IDs and owns
the durable journal. Context isolation, Chromium sandbox, offline resource policy
and application single-instance ownership are preserved. The practice session is
closed on renderer navigation/crash, app shutdown and learner-profile switching.
Generation checks fence late results. Real-profile selection never supplies a
practice writer path.

The runtime now includes the standard `jdk.compiler` module so the existing Java
runner availability check can succeed. No compile/run/evaluation command is exposed.

## Product and disclosure boundaries

Practice Workspace is separate from historical Mission Console. Its explicit
connection consent explains disposable storage, initial draft creation and older
JavaFX coexistence limitations. Only Java-current eligible exploratory practice
is selectable. The existing default 20-minute budget is preserved; this slice
cannot change preferences to manufacture more eligible candidates. Exact IDs,
versions, current live profile/session and committed receipt are visible. Neither
historical PASSED nor a saved recommendation enables Start.

The original four draft skills and sixteen draft stage IDs remain unreviewed and
unplayable. The workspace has no hint, solution, edit, save, submit, grade, completion,
XP, mastery, assignment or reward command. The compiler preview and evidence-backed
Ascension Tree are unchanged. Ordinary JavaFX services and policies are preserved.

Screenshots intentionally capture real Java candidate metadata before opening.
Protected instruction views have no automatic screenshot/trace recording in the
packaged tests. This is local disclosure control, not DRM: a learner with filesystem
access can inspect bundled assets, and authorized users can manually capture their
own screen. No restricted prompt is added to static renderer assets or PR images.

## Unsupported behavior and next slice

- Real learner-profile writing, migration, backup/rollback and cross-platform
  validation are deferred. Windows/macOS practice connections fail closed.
- Cooperative ownership is not OS sandboxing against a same-user arbitrary program
  or an old JavaFX binary deliberately pointed at this private path. Never share or
  import this experimental profile into those applications.
- Cancellation does not revoke previously committed exposure. An unresolved intent
  is retained on permanent catalog/eligibility failure rather than silently erased.
- No editing or submission. A separately reviewed next slice should reuse the
  existing draft repository and PlayerSystemService save contract with versioned
  conflict detection, then the durable evaluator's frozen submissions and receipts.
- No Gate 3 work, new syllabus approvals, new graders or real-profile opt-in gate.

## Adversarial review findings

| Question | Finding / protection |
| --- | --- |
| Two profile writers? | Electron single-instance lock plus Java canonical ProfileLock before bootstrap. Separate Java process contention is tested; existing JavaFX canonical-owner tests remain enabled. Older/manual writers are explicitly unsupported. |
| Crafted draft/reserved ID? | Exact original bundle PRACTICE allowlist, persisted manifest equality, asset/binding validation and current Java exploration rule. All sixteen draft stage IDs and every reserved cohort are rejected in isolated tests. |
| Renderer bypass? | Main-frame/argument allowlist, main-generated durable command, exact Java live session and final Java policy check; no historical-console action endpoint. |
| Prompt before commit? | Transaction returns identity only; prompt comes from existing after-commit resume. Forced exposure rollback produces no receipt/content. |
| Lost reply duplicates exposure? | Main journal survives; Java immutable command refs and deterministic presentation ID bind every retry. Status is metadata-only; explicit replay resumes the original receipt. |
| Stale session? | New Java UUID each process; request/profile/command/result correlation and host/component generations. Learner switching also disconnects the experimental owner. |
| Cancellation ambiguity? | Explicitly retained intent, no automatic new command or rollback claim. Acknowledge requires a durable receipt. |
| Content in diagnostics? | Redacted finite codes, bounded discarded stderr, no source/body in command table or journal; plain-text rendering, no HTML evaluation; screenshot/trace capture disabled for protected-view tests. |
| Unsafe migration? | Unmarked/nonempty directories rejected, learning marker differs from library marker, exact schema/quick_check/foreign_key_check required. Existing DB never initialized/migrated. |
| XP/evidence/mastery side effects? | No overview/recommend/evaluate/progression service entry point. Exploratory rule extracted from existing policy without estimate publication. Presentation's existing initial session/draft are the only additional domain effects. |

Review caught and fixed the sidebar controls falling below shorter viewports after
adding a destination. A subsequent post-start refresh failure now clears live
state and protected instructions while retaining the durable recovery intent;
failed acknowledgements do not silently discard the Java receipt. These are covered
by the existing navigation regression and new packaged practice recovery tests.

Transport tests exercise extra/private fields, oversized output, wrong session,
timeout and crash; package tests execute real Java opening, renderer reload,
application restart, lost committed reply/cancellation, unauthorized frames and
post-start refresh failure. Native platform and hostile same-user filesystem
containment are not claimed. No security sandbox or anti-cheat boundary is added.

Dependency review: no runtime/build dependency or lockfile changes. `npm audit`
reports the existing low esbuild Windows-development-server advisory
GHSA-g7r4-m6w7-qqqr; no moderate/high/critical advisory. The packaged application
runs no development server. A separately tested toolchain upgrade remains a
follow-up, not an incidental change in this learning slice.

The existing keyboard regression also exposed a native modal Tab-wrap gap. The
shared Dialog now explicitly wraps the first/last enabled visible controls,
including Shift+Tab; Escape and return-to-trigger focus remain owned by its
existing lifecycle. No keyboard assertions were weakened.

## Files and verification

- Java: `PracticeApplicationMain`, `PracticeProtocol`, `PracticeApplicationService`;
  dedicated learning mode in `ExperimentalProfile`; `PracticeEligibilityService`
  and the shared exploratory predicate in `AdaptiveQuestPolicy`.
- Electron: `practice-session`, `practice-owner`, `practice-journal`, finite main
  handlers and preload methods. Existing snapshot readers are unchanged.
- React: `practice-contract`, `practice-workspace`, navigation/provenance and scoped
  styles; accessible shared dialog wrapping. No static challenge content added.
- Runtime: add `jdk.compiler` to the existing Java 21 jlink runtime; no new compiler
  implementation or execution command.
- Tests: isolated `PracticeApplicationIntegrationTest`; real-process and malicious
  pipe tests; packaged `practice.spec.ts`; exact preload allowlist updated.

```sh
# Java 21 and an active Xvfb display
DISPLAY=:108 mvn -B test
# Desktop, after preparing the Java runtime (do not overlap Maven builds)
npm run prepare:java
npm run build
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  DISPLAY=:108 sh scripts/offline-linux.sh npm run test:e2e
```

The hard-crash-before-commit transport fixture uses Python's standard SQLite
module only in an isolated test directory to hold a writer barrier, kills the
owned Java process, and verifies the original command can recover once. Python
is a Linux test prerequisite, not an application dependency or runtime grader.

Final counts and CI links are recorded in the draft PR. Infrastructure-invalid
runs (overlapping Maven compilation and a missing X server) were discarded and
rerun; they are not counted as successful verification.

Actual packaged Linux screenshots, real Java metadata from disposable profiles:

| Layout | Dark | Light |
| --- | --- | --- |
| 1280 px | [Desktop dark](screenshots/practice-1280-dark.png) | [Desktop light](screenshots/practice-1280-light.png) |
| 760 px | [Narrow dark](screenshots/practice-760-dark.png) | [Narrow light](screenshots/practice-760-light.png) |

Capture uses `CODEFIT_CAPTURE_PRACTICE_SCREENSHOTS=1`. The protected instructions
are deliberately absent from these distributable images. The packaged tests
separately verify the real after-commit instruction view and its receipt.
