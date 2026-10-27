# Experimental practice drafts — lifecycle audit and implementation plan

Baseline: c42a394eca6c40d35635fd5d8944679dc1fca17a (#275). Scope: #261/#268/#256, Linux disposable profiles only. This audit precedes implementation.

## Existing owners and side effects

- `ChallengePresentationService.present(Connection, Request)` commits exposure and creates the existing per-Problem solving session and Java draft only if absent. The starter can include a harness; `PlayerSystemService.work` removes that exact suffix before rendering source. Protected assets must not cross IPC.
- `ChallengePresentationService.resume` validates a committed receipt/binding, reads assets/draft, and does not append exposure. Its context helper acquires the catalog writer lock, so it is not a snapshot-reader API. Desktop must additionally restrict it to its own original PRACTICE commands and exact bundled versions.
- `JavaSolutionDraftRepository` owns a single draft per Problem, shared by related presentations. `update` has no revision condition. `JavaSolutionWorkspaceService` is for ordinary Problems and rejects Evolution bindings; bypassing that guard is inappropriate.
- `PlayerSystemService.save(Draft)` transactionally updates the existing Java draft and `evolution_working_notes` (reflection/assistance per presentation). It does not execute, evaluate, recommend, estimate or reward. `overview` does recommend/reconcile and must not be used here.
- `EvolutionController` debounces saves and tracks edit generations, but does not provide database optimistic concurrency. Its ordinary behavior must remain unchanged.
- `ProblemSolvingSessionRepository` owns the existing phase/time record; draft saves do not update phase/time or attempts.
- `PracticeApplicationService` owns a marked exclusive experimental DB. `PracticeOwner` fences the process/session; `PracticeJournal` contains only immutable start references. Acknowledgement removes the host pending start, not the committed Java presentation. Rediscovery must query committed commands/presentations, not create a new start.

## Chosen revision boundary

An additive, transactionally installed experimental-only metadata schema will supply a monotonic profile-wide draft revision and immutable save receipts (identity, input digest, expected/result revision, outcome). Source/reflection remain exclusively in existing Java tables. A global revision conservatively conflicts even for unrelated drafts: it is simple, bounded and also protects shared Problem drafts across presentations. SQLite triggers advance it on draft/working-note changes, including legacy repository updates. Atomic compare-and-swap and the existing Java save execute in one transaction. No timestamp concurrency checks, no learner SchemaMigrator change.

Only a locked, marked, integrity/schema-validated disposable practice profile receives this additive upgrade. Existing real learner profiles and library experiments do not. Old JavaFX semantics are preserved; new metadata detects cooperating repository changes, but old binaries must never open this private profile. No hostile same-user process containment is claimed.

## Durability and privacy

Acknowledged saves survive restart. Each command has a stable ID and hash of exact presentation/revision/source/reflection. Same ID/different input fails. Lost responses require receipt reconciliation before further saves; a new command is not a substitute for an ambiguous one. Host journal metadata contains only IDs/revision/digest, never source or notes. In-memory edits not acknowledged by Java can be lost on renderer/OS hard crash; the UI must say so and guard ordinary navigation/close. No promise of last-second forced-exit flush.

Resume validates the original committed presentation and returns the current shared draft plus per-presentation notes; it does not restore an older draft over newer content. Only authorized editor IPC returns source/notes/instructions. No hidden harness, tests, solutions, evaluation execution, rewards, mastery or unreviewed syllabus content.

Implementation, contracts, verification and adversarial findings will be recorded below as they are completed.

## Implemented contracts

The dedicated practice protocol retains envelope version `v:1` and adds finite, strictly parsed methods. Every request carries a fresh transport `id` plus exact `profileId`/`sessionId`; renderer cannot select a path or SQL. An incompatible old sidecar fails closed on unknown methods. No learner snapshot protocol changes.

| Java method | Additional request fields | Successful result fields |
| --- | --- | --- |
| `practice.presentations` | none | `presentations`: latest at most 32 original practice receipts, ordered by persisted presentation time then ID |
| `practice.presentation.resume` | `presentationId` | exact `receipt`, authorized `instructions`, `draft` |
| `practice.draft.read` | `presentationId` | `draft` |
| `practice.draft.save` | `commandId`, `presentationId`, `expectedRevision`, `source`, `reflection` | immutable `saved` receipt |
| `practice.draft.status` | `commandId`, `presentationId` | immutable `saved` receipt or null (not committed) |

`draft = {presentationId, revision, source, reflection}`. `saved = {commandId, presentationId, digest, expectedRevision, revision, outcome: SAVED|CONFLICT}`. Digest is SHA-256 of four UTF-8 strings (presentation, decimal expected revision, source, reflection), each prefixed by its unsigned four-byte big-endian byte length. No timestamp participates in concurrency.

Revisions are integers in `[0, 9007199254740000)`, safe in JavaScript. Source is at most 32,768 UTF-16 code units, reflection 8,000, instructions 24,000; malformed Unicode is rejected, never repaired. Input/output JSON frames are bounded to 512 KiB (including worst-case JSON escapes), with depth/duplicate-key/field/version checks on both sides. Private DB rows are bounded by byte length before loading, including embedded-NUL rows. Receipt/presentation/transport identities and save digests are correlated before use. Timeout remains 15 seconds; stdout/stderr, heap, shutdown escalation and owning-frame restrictions are inherited from #275. Other protocol frame limits remain unchanged.

The preload adds `resumePractice`, `readPracticeDraft`, `savePracticeDraft`, `reconcilePracticeDraft`, and a boolean-only `setPracticeDraftDirty` close guard. No generic RPC or filesystem bridge. Main generates save command IDs and fsyncs `pending-draft.json` before transport; its schema contains only profile/presentation/command/revision/digest. One pending ambiguous save blocks different input. A definitive Java receipt clears that pending metadata. An explicit reconciliation checks the original ID; null means no commit under the current exclusive owner, and does not automatically dispatch another save. The renderer then reads the latest draft and requires an explicit keep/load decision before restarting autosave. A lost renderer reply after main cleared the receipt is resolved by reading the persisted draft, not by claiming unacknowledged edits were recovered.

## Database and transaction details

`PracticeDraftSchema` runs only after the existing marked learning profile passes ownership, integrity, foreign-key and exact schema-25 checks. It installs two private metadata tables and triggers atomically, also supporting an intact PR #275 experimental profile. It never calls SchemaMigrator on an existing profile. Partial/mismatched metadata definitions fail closed rather than being repaired silently.

- `desktop_draft_clock`: one monotonic revision. Six triggers track INSERT/UPDATE/DELETE on the existing draft and working-note tables. This deliberately covers shared Problem drafts and legacy repository writes.
- `desktop_draft_saves`: immutable command fingerprint, original expected revision, resulting revision and SAVED/CONFLICT. No content copy or generic attempt store. Update/delete triggers prohibit rewriting receipts.
- Save obtains the existing catalog writer lock, validates the exact committed authorized context/binding, checks replay, then executes `UPDATE ... WHERE revision=?`. On success, `PlayerSystemService.save(Connection,Draft)` writes the existing source/notes and the receipt in that same FULL-synchronous SQLite transaction. Any exception rolls back source, notes, revision and receipt together. No execution occurs inside or outside this transaction.
- A conflict is itself a durable receipt; it never alters draft content. Replaying it remains a conflict even if a later read shows another revision.
- Initial source removes the exact trusted harness suffix using the existing JavaFX convention. Saves containing the trusted harness are rejected; learner edits otherwise round-trip exactly. Main class, stdin, expected output and existing assistance metadata are preserved; only source/reflection are editable here.
- Resume validates the desktop-owned command, bundled/installed exact PRACTICE catalog, assets, backing and existing solving session. It reads the current shared draft, not an obsolete copy associated with the start command. Neither resume nor save appends exposure, recommendations, evidence, attempts, XP or rewards.

The only shared Java service change is an overload exposing its existing save body on a caller-owned transaction. Ordinary JavaFX saves retain their original transaction semantics; there is no new global draft column or migration.

## Editor and interruption behavior

The native textarea retains Chromium text input/undo/redo, with an escaped React lexical-color layer, line numbers, four-space Tab indentation and newline indentation. Shift+Tab leaves the editor; Ctrl/Cmd+S saves. It uses bundled DejaVu Sans Mono with its license, no new npm dependency, language server, compiler, remote font or WebView. Highlighting is cosmetic and not Java validation. Unterminated comments consume the remaining bounded input once.

A 700 ms debounce coalesces edits. One save is in flight; its captured source/revision remains immutable. An acknowledgement updates only the persisted baseline and never overwrites newer editor text. Newer edits schedule a subsequent save with the acknowledged revision. Errors stop autosave, retain in-memory edits and expose reconciliation; conflict comparison never loads stored text without explicit consent.

Ordinary route/profile changes and disconnect guard unconfirmed work. Main's native close/quit prompt and renderer beforeunload cover reload, including a delayed dirty-boolean IPC. Forced termination cannot guarantee last-second saving. Only acknowledged content is promised across restart. Receipts survive process interruption; no source is backed up into the operation journal, browser storage, telemetry or logs.

## Adversarial review and limitations

Review checked source-to-presentation binding, committed exposure, stale CAS, duplicate and conflicting commands, global revision changes through legacy writes, delayed replies, ambiguous completion, navigation/reload/quit, harness disclosure, unsupported syllabus IDs and exact IPC authorization.

Confirmed issues addressed during implementation:

- The old unversioned draft update was insufficient for optimistic concurrency: added a SQLite CAS and triggers scoped to the experimental profile.
- Editor visibility before the post-open metadata refresh could race another IPC call: editor waits until refresh completes.
- Quit initially stopped the Java owner before the last renderer leave decision. Window close/beforeunload now completes before service shutdown, so declining quit retains a usable owner.
- An early dirty notification could lag a reload: beforeunload now independently prompts rather than relying only on main's boolean.
- Prototype-state navigation could bypass a dirty editor's leave guard: it now uses the same explicit guard.
- Repeated recovery could retain an earlier comparison approval: each failed save now requires a fresh reconciliation before keep/load controls unlock.
- Unterminated comment highlighting could repeatedly scan the suffix: the lexer consumes it to end-of-input.

Residual limits: Linux only; exclusive locks govern cooperating current binaries, not malicious same-user programs or older binaries deliberately pointed at this private path. Do not share this experimental profile. Real-profile writes, backup/rollback/migration, Windows/macOS validation and multi-writer merge UX remain disabled/deferred. The conservative profile-wide revision may cause unrelated-draft conflicts. Only the latest 32 presentations are listed; current published catalog/retry policy bounds normal practice. Corrupt context/metadata fails closed, potentially making the workspace unavailable until an independently reviewed repair. No destructive cleanup policy is added. Private SQLite content is local plaintext protected by owner-only permissions, not encrypted storage.

Next reviewed slice should use the existing Java evaluator's immutable artifact/submission/receipt lifecycle. Add submission independently of draft-save acknowledgement; never grade the editable draft or infer mastery/XP from saving. No #261/#268 closure or Gate 3 work is implied.

## Visual evidence and verification

Actual packaged app, synthetic non-sensitive Java source only; authorized challenge instructions are collapsed before capture. No UI fixtures impersonate the Java owner. Both themes at 1280 and 760 pixels:

- [1280 dark](screenshots/draft-1280-dark.png)
- [1280 light](screenshots/draft-1280-light.png)
- [760 dark](screenshots/draft-760-dark.png)
- [760 light](screenshots/draft-760-light.png)

Commands: `DISPLAY=:109 mvn -B test`; `JAVA_HOME=<Java21> CODEFIT_MAVEN=<Maven> npm run prepare:java`; `npm run build`; `npm test`; `npm run package`; `DISPLAY=:109 CODEFIT_DESKTOP_EXECUTABLE=<packaged binary> sh scripts/offline-linux.sh npm run test:e2e`. All tests use isolated profiles and retain real-profile sentinel checks. Java/Node/packaged totals and final-commit CI links will be recorded in the PR after completion.

### File inventory and dependency review

- Java boundary: `PracticeDraftService`, `PracticeDraftSchema`, `PracticeProtocol`, `PracticeApplicationMain`, and experimental-only installation in `ExperimentalProfile`. `ApplicationProtocol` gains a bounded-frame overload without changing other callers' limits. `PlayerSystemService` gains the caller-owned save overload.
- Electron: `practice-owner`, `practice-session`, metadata-only `draft-journal`, the existing journal's directory accessor, and finite main/preload/TypeScript contracts.
- React: `PracticeEditor`, `JavaEditor`, `draft-navigation`, practice resume/editor integration, guarded existing navigation, appended theme styles and a licensed local monospaced font.
- Tests: isolated Java draft integration, real-process Node draft tests, packaged editor/failure/visual tests; existing exact preload allowlist and frame-limit fixture updated to the explicit new contract.
- Documentation and four synthetic packaged screenshots in this directory.

No npm or Maven dependency/lockfile change. DejaVu Sans Mono is copied from the environment's DejaVu package with its redistribution license (`desktop/public/fonts/DEJAVU-LICENSE.txt`); SHA-256 `54bf827eb99404e8f430c330ad30f063334f637eba0109b6a18d4f566a8e9dd8`. `npm audit` reports the same existing low-severity esbuild development-server advisory as the baseline, with zero moderate/high/critical findings. The packaged app has no development server. No download, network, telemetry or new native module is introduced.

Local verification completed during review: full Java 21/Xvfb **1,957 tests, zero failures/errors/skips**; final focused draft/opening/PlayerSystem regression **26 passed**, including 10 new isolated draft tests; full Node **75 passed**; full sandboxed packaged Electron **72 passed**. Final hardening is followed by another complete desktop run and final-commit GitHub CI, reported in the PR. TypeScript/Vite build, Java runtime preparation and Linux packaging pass. An initial Electron launch attempt used a stale X socket and was rerun with a live Xvfb server; a stale-editor fixture race was corrected by waiting for completed opening. Assertions and isolation were not weakened.

Saved receipts remain auditable; the existing one-row draft model intentionally retains the latest source rather than a historical source archive. Undo/redo is in-memory editor history. Restoring an older source revision after a newer acknowledged save is outside this slice.
