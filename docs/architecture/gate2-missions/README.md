# Recorded Mission Console — read-only Gate 2 slice

Baseline `4e84ede0b1570d5ef0bd25618b76de4602f71fce`. Only open PR at audit is
#255, the superseded JavaFX visual branch; no shared implementation is taken from it.

| Existing owner | Actual behavior | Read-only desktop decision |
| --- | --- | --- |
| PlayerSystemService.overview | Calls recommendation supplier, reconcilePendingEffortRewards and progression.snapshot; not a pure read | Never call |
| AdaptiveQuestService.recommend / recommendCurrent | Captures estimates, publishes immutable decisions; current path reconciles milestones | Never recompute; report NOT_EVALUATED |
| AdaptiveQuestService.respond / start | Writes learner choices; starts authorized presentation | Future writable slice only |
| DailyQuestService.getActiveQuest | find-or-generate-and-save for today | Never call; daily engagement quests are not Evolution missions |
| QuestCatalogRepository.transaction | Acquires writer lock and commits even for callers performing reads | Never use transaction wrapper; compare exact known bundled manifests using caller-owned query-only connection |
| QuestCatalogRepository publish / JavaTrainingPack.seed | Validates and writes catalog/backing records | Never seed/migrate the learner snapshot |
| JavaTrainingPack.quests | Reads bundled immutable quest definitions | Safe metadata authority; only expose title/ref/cohort after matching exact persisted bytes and digest |
| ChallengePresentationService present/resume/reveal/practice | Authorizes content and/or persists exposure, familiarity, hints and practice | No invocation, even for navigation |
| DurableEvaluationRepository | Source validation and immutable revision decoding; public default wrappers open their own transactions | Add bounded caller-connection metadata read reusing its validator; do not export artifacts/checks/diagnostics |
| ChallengeEvaluationCoordinator prepare/resume/cancel | Frozen submission, execution leases, revisions, attempts/evidence and reward effects | No invocation; historical stages are not live process/recovery eligibility |
| ReviewService getDueCards/getAdaptiveSessionCards | Reads private card content and applies scheduler/session policy; review/graduate/suspend mutate cards/history/progress | Reuse only existing safe count projection at UTC capture date; no session selection or question text |
| JavaSolutionDraftRepository / PlayerSystemService.save | Own editable code/working notes, writes drafts | Do not read private drafts; future writer uses existing save lifecycle |
| EvolutionController | Uses PlayerSystemService for overview, authorized Work, evaluation/recovery and draft persistence | Keep unchanged; desktop history must not mimic its live actions |

The new projection uses the existing consented LearnerSnapshot, query_only SQLite,
its read deadline and caller-owned coherent transaction. No source migration,
automatic reconnect, profile write, reward/assignment/mastery/exposure mutation or
new database. Historical submissions and recommendations do not imply current
eligibility. Author-draft Knowledge Compiler stage IDs remain outside this path.

## Read boundary and contract

Renderer `readLearnerMissions()` takes **zero arguments**. Preload invokes only
`codefit:learner:missions`; main checks the owning top-level frame, exact argument
count and active learner generation. The existing `LearnerSessionOwner` serializes
and coalesces reads on the consented Java process. Its protocol-v3 finite command
is `learner.missions.read`, with the existing exact profile/session identity.
Java dispatches only after authorization. No generic RPC, SQL, path, URL or
renderer-selected source is added.

The successful result is protocol result v1, `kind: learner-missions`, with:

| Fields | Meaning and bounds |
| --- | --- |
| `source`, `readOnly`, `schemaVersion` | Literals `learner-snapshot`, true, 25 |
| `profileId`, `sessionId`, `capturedAt` | Existing pinned snapshot identity and UTC capture time; never live state |
| `currentEligibility`, `activeAssignment`, `actionsEnabled` | Always `NOT_EVALUATED`, `NOT_EVALUATED`, false |
| `catalogStatus`, `quests[]` | AVAILABLE / INCOMPLETE / UNAVAILABLE; known bundled exact quest id/version/title/cohort only after persisted manifest and digest match; max 32 |
| `historyStatus`, `historyTruncated`, `activities[]` | AVAILABLE / UNAVAILABLE; latest 20 submission rows in descending durable sequence order; one extra row detects truncation |
| Activity fields | command, presentation, questId/version, challengeId/version, submittedAt, stage, outcome/null, revision/null, withdrawn, metadata, evaluatedAt/null |
| `decisionStatus`, `decisionsTruncated`, `decisions[]` | Latest 10 saved decision **header references**, id and asOf only; policy inputs/outputs and current validity are not interpreted |
| `review` | recordedReviews, dueScheduledCards, asOfDay at snapshot capture UTC day; counts, not selected review content or a current assignment |

Exact executable schemas: [Java serializer](../../../src/main/java/com/codefit/desktop/LearnerProtocol.java),
[TypeScript strict schema](../../../desktop/src/mission-contract.ts).
Outcome vocabulary comes from existing durable evaluation contracts. A recorded
PASSED is an historical evaluator outcome, not a new mastery conclusion. A
withdrawn revision exposes no positive outcome. PREPARED/RUNNING/EVALUATED are
historical stages, not claims that a process currently runs or recovery is allowed.

The projection has no total-count claim for truncated activity/decision lists.
Unknown or conflicting exact quest versions do not resolve to a newer version.
Unsupported/corrupt sections become UNAVAILABLE; a valid empty section remains
AVAILABLE with no records. Invalid counts or snapshot-level incompatibility fail
the whole request with existing redacted errors.

## Integrity, privacy and lifecycle

The caller-owned coherent read transaction uses the existing query-only snapshot
connection and SQLite progress deadline (10 seconds). No repository writer wrappers,
source migrations, seed operations, reconciliation or automatic reconnection are
used. Repeated source-byte comparisons and isolated-database integration tests
cover populated and empty profiles.

Durable history reuses the existing frozen submission/revision validators. The
Java process internally reads bounded private frozen records to verify their
hashes, source context, exact presentation and result. It exports **only** the
allowlisted metadata above. Neither source bytes, reflection, rubric checks,
compiler diagnostics, hints, answers, decision payloads, private drafts nor P2P
secrets cross this boundary. This does not authenticate learner-owned SQLite
against a malicious owner; it checks internal consistency under the existing
local integrity model.

History reads guard each frozen BLOB at 2,000,000 bytes before decoding; catalog payloads
are capped at 200,000 bytes, identifiers at 128 characters and titles at 512.
Read paths reject views and virtual tables. The mission wire response is capped
at 64 KiB, enforced before renderer delivery. Existing bounded subprocess stdout,
request timeouts, cancellation/termination, session authorization and redacted
errors remain authoritative. Profile switch/disconnect and renderer reload fence
late results. Cancelling the loading view disconnects the reader; it does not
cancel, resume or change any historical learner submission.

Full learner snapshots retain the PR265 limitations: private local files are not
encrypted archives; filesystem access can reveal copied learner data. This PR
adds no snapshot retention/deletion policy. Existing explicit disconnect and
capture controls remain; historical files are not silently deleted. Windows/macOS
real-profile access remains disabled pending native validation.

## UI boundaries

Connected Mission Console replaces prototype missions with a recorded journal,
selected-submission inspector, exact published quest metadata and saved decision
references. It shows capture provenance and UTC review-count date. Selection and
filtering are local display operations. Open/resume and evaluate/review controls
are disabled with an explanation; no execution endpoint exists. No-current-status
messages are deliberately prominent. With no learner connected, the existing
prototype remains visibly labelled preview. A failed connected read cannot fall
back to fictitious missions.

Ascension remains backed by the existing exact graph/historical estimate API.
Curriculum Preview remains a distinct Java public-safe metadata capability. None
of its four unreviewed skill IDs or sixteen stage IDs are inserted into mission
catalogs or treated as executable challenges. No approval or curriculum release
permission is introduced. JavaFX workflows, authorizations and owners are unchanged.

## Next separately reviewed writable slice (#268)

1. Define an explicit opt-in **live writer session**, distinct from the frozen
   snapshot reader. Establish single-owner/coexistence rules with JavaFX before
   allowing writes; a stale snapshot cannot authorize an action.
2. Add finite versioned commands for opening a published challenge via existing
   `ChallengePresentationService`. Validate exact quest/challenge references and
   expected live session. Persist exposure in its existing transaction **before**
   returning restricted content; never resolve Knowledge Compiler draft stages.
3. Reuse the existing `PlayerSystemService`/Java workspace draft lifecycle and
   existing draft repository. Serialize saves with explicit draft revision/conflict
   handling. Keep mutable drafts separate from frozen submissions; no second
   draft or attempt store in Electron.
4. Use `ChallengeEvaluationCoordinator` for stable submission commands, freezing
   artifact and authorization context, bounded existing Java execution, atomic
   attempts/evidence, durable receipt and idempotent replay. Same command with a
   different payload must fail. Do not hold SQLite writes during execution.
5. Route cancellation and interrupted-submission recovery through existing Java
   authorization and coordinator semantics. Distinguish request cancellation from
   cancelling a durable evaluation. After lost response, retrieve the exact receipt
   before offering retry; no new command ID as an automatic recovery strategy.
6. Keep restricted content out of static assets, logs and generic IPC. Deliver it
   only after authorized exposure, bind it to session/presentation, fence responses
   across switch/reload, and make retention/clipboard/cache policies explicit.
7. Invoke reward reconciliation only through the established Java policy at an
   authorized lifecycle point; never award from React or a dashboard read. Refresh
   a consented snapshot explicitly after writes; don't relabel old snapshots live.
8. Require cross-platform runner validation, isolation, owner-conflict, crash,
   duplicate-command, privacy and JavaFX regression tests before enabling actions.
   This proposal grants no new permission or readiness claim.

## Adversarial review and residual limits

- Mutating overview/recommendation/daily-quest paths are excluded by construction;
  this projection uses only explicit snapshot reads and immutable bundled metadata.
- Frozen result validation and exact presentation binding prevent displaying a
  result against a different persisted challenge. Missing catalog metadata remains
  visibly unavailable rather than guessing equivalence.
- Future timestamps are unavailable; historical running stages and saved decision
  headers cannot imply current assignments. Withdrawn positive results are hidden.
- Strict schemas reject extra/private fields and false action-enablement flags;
  bounded SQL reads and wire limits constrain malformed database amplification.
- Session/generation fences are checked both in Electron and the React view;
  delayed prior-profile responses cannot populate a replacement profile.
- Catalog labels are bundled original CodeFit metadata only. No reserved challenge
  body, unpublished mission instruction or unreviewed compiler stage is delivered.
- No new dependency, schema migration, policy, learner writer or network service.
- Decision references are intentionally not full validated recommendations; the
  console does not interpret their saved output, policy compatibility or eligibility.
- Linux packaged validation does not establish Windows/macOS support or secure
  archival deletion. Snapshot outcomes can be historical even when structurally
  valid; the UI consistently labels their capture context.

Dependency audit (2026-10-25): no dependency or lockfile changes. `npm audit`
reports one existing **low** development-tool advisory,
[GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr),
for esbuild's Windows development-server file access. This packaged Linux slice
runs no development server; native Windows learner access is still disabled.
Track a separately tested esbuild upgrade rather than claim a clean dependency
audit or expand this feature into an unrelated toolchain upgrade. No moderate,
high or critical npm advisory was reported.

## Changed-file inventory

- Java snapshot boundary: `LearnerMissionProjection`, `LearnerProtocol`,
  `LearnerProfileMain`, `LearnerSnapshot`.
- Existing durable owner: `DurableEvaluationRepository.recentMetadata` (bounded
  caller-transaction read only; no changes to submission/evaluation write paths).
- Desktop boundary: `electron/learner-session.ts`, `electron/main.ts`,
  `electron/preload.ts`, `src/contract.ts`, `src/learner-contract.ts`,
  `src/mission-contract.ts`.
- React: `src/learner-missions.tsx`, `src/main.tsx`, scoped `src/style.css` additions.
- Tests: `LearnerMissionProjectionTest`, synthetic `MissionFixture`, host fixture
  launcher, `mission-session.test.ts`, `missions.spec.ts`, existing bridge allowlist
  and reader mocks updated for the new finite method; learner-route regression
  now requires real mission history, and the prototype theme test waits for the
  initial React theme before attempting to toggle it.
- Documentation: this audit/contract/proposal and four packaged screenshots.

No migrations, dependency changes, protected content assets, JavaFX controller
changes or writable production commands are included.

## Verification (2026-10-25)

All fixtures use temporary isolated SQLite files; no real learner profile was
opened or changed. Full packaged tests ran with Chromium sandbox enabled under
Xvfb in a Linux network namespace with only loopback (no external route).

```sh
# Java 21, repository root
xvfb-run -a mvn -B test
# Desktop; prepare:java uses Java 21 and Maven, never a learner profile
npm run prepare:java
npm test
npm run build
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  xvfb-run -a sh scripts/offline-linux.sh npm run test:e2e
```

Java 21 full suite under Xvfb: **1,927 passed**, no failures, errors or skips.
Node: **65 passed**. Packaged Electron: **56 passed**, including ten mission tests.
TypeScript/Vite build and Linux packaging pass. Exact CI links are recorded in the pull request. The nine new Java projection tests cover
real durable records and source-file preservation, and the existing full suite
covers JavaFX, migration, evidence, evaluation and P2P behavior.

Four actual packaged screenshots (synthetic fixture, not real learner data):

| Size | Dark | Light |
| --- | --- | --- |
| 1280 × 900 content viewport | [Desktop dark](screenshots/missions-1280-dark.png) | [Desktop light](screenshots/missions-1280-light.png) |
| 760 × 900 content viewport | [Narrow dark](screenshots/missions-760-dark.png) | [Narrow light](screenshots/missions-760-light.png) |

Images are full-page captures; the OS scrollbar accounts for the 15-pixel image
width difference. Inspection confirms readable labels, distinct frozen submission
identity, visible keyboard focus, disabled action controls and no horizontal
page overflow. Screenshot reproduction uses
`CODEFIT_CAPTURE_MISSION_SCREENSHOTS=1` with the packaged Electron suite.
