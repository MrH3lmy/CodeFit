# Curriculum Preview: read-only application boundary

## Side-effect audit (before implementation)

Baseline main: `6aedbbdbe8535d4575677826d773baa981b30766` (#271 + #272).
`KnowledgePreview.loadBundled()` reads two fixed classpath resources, validates the
compiled SHA-256 and schema, and compares identifiers with the pure
`JavaDebuggingSkillSeed.graph()`. It does not use a repository, SQLite, migrations,
preferences, evidence, evaluation, adaptive policy or rewards. `PublicPreview` is
immutable metadata, not an approval token. The existing CLI's explicit JSON export
is the allowlist; the private checkpoint is omitted. CLI publication writes build
output and must not be called by this capability.

The existing application sidecar owns an experimental database; learner sidecars
capture full private snapshots. Neither is appropriate here. Add a separate finite
one-shot Java entrypoint using the existing runtime packaging, strict framing,
process limits, parent supervision and main-frame authorization patterns. Do not
initialize either existing owner to serve a preview. Graph repositories and
estimate services remain untouched. No TypeScript curriculum fixtures are a source
of production content.

## Exposure gate

This request authorizes local implementation and visual review, not distribution
of a syllabus to an unspecified audience. Default packaged and development launch
keeps preview reads disabled. Explicit host launch argument
`--enable-curriculum-preview-local` enables this exact bundled metadata for local
review only. No renderer argument, preferences toggle or learner consent changes
this gate. The screen explains the gate rather than serving fallback content.
Independent owner audience authorization remains necessary before a separate
review changes the default. Content stays UNREVIEWED even when locally visible.

## Exact API and lifecycle

Renderer preload exposes only:

```ts
readCurriculumPreview(): Promise<CurriculumResult>;
cancelCurriculumPreview(): Promise<void>;
```

Main channels `codefit:curriculum:read` and `codefit:curriculum:cancel` accept **zero
arguments**, only from the owning, alive BrowserWindow main frame at the exact
bundled entry URL. No path, graph, profile, prompt or URL is accepted. Read calls
coalesce during one in-flight request. No persistent preview cache is created.
Refresh is an explicit fresh validated read. Renderer unmount/reload/crash and
app shutdown invalidate the owner generation and terminate its child; late results
cannot populate a replacement view. Cancellation never stops a learner/deck owner.
Renderer effects independently fence stale results and clear old content on retry.

Java stdin (one newline-terminated UTF-8 frame, at most 256 bytes):

```json
{"v":1,"id":"<main-generated UUIDv4>","method":"curriculum.preview.read"}
```

Java stdout (one complete response and a clean exit, at most 32 KiB plus newline):

```ts
type CurriculumWire =
  | { v: 1; id: string; ok: true; result: {
      v: 2; kind: "author-draft-curriculum-preview";
      source: "bundled-author-metadata"; learnerBound: false;
      preview: PublicPreviewV1;
      coverage: LocalReviewCoverageV1;
    } }
  | { v: 1; id: string; ok: false; code: "INVALID_CATALOG" };
```

`PublicPreviewV1` is the exact explicit JSON export from #271. The full executable
schema is [`curriculum-contract.ts`](../../../desktop/src/curriculum-contract.ts).
Every object rejects extra fields; stages, IDs, source-item relationships,
collection bounds, mapping gaps and literal restrictions are checked. The host
also hashes the schema-validated serialized preview and requires the pinned public
export hash. No titles, prerequisite lists or stage content are copied into React.
The renderer validates the strict result again, before rendering plain React text.
No raw HTML or Markdown renderer is used.

### Five-book local-review projection

The same gated read now returns a separate versioned coverage projection from the
fixed original-only `coverage-ledger-input.json` merged in #306 (`1ba7a9b`).
Java verifies the bounded bundled ledger against a compiled byte pin and projects
only allowlisted metadata. Maven bundles that exact checked-in ledger; React has
no ledger copy, direct SQLite access or replacement inventory. The #309 validator
remains test-classpath-only and is not a production importer.

The delivery report attributed in #298 is not independently enumerated or verified:
504 structural units, 157 preliminary mappings and 347 unmapped units are
**source-attributed historical claims**, not current runtime coverage. Every
per-book preliminary mapping count remains `UNKNOWN`. Effective Java's 90/90
first-pass author notes are not approvals. The 419 mission blueprint slots and
424 nonadditive revision events are distinct measures; neither is a playable
mission count. Book inventory, author notes, independent review, runtime imports
and learner validation remain separate. The four pilot skills are not additional
book units and still have no approved mappings or independent review.

The result envelope advances to version 2; request/wire framing stays version 1
and the existing public pilot export stays byte-identical. Both projections must
validate before publication. Missing, malformed, stale or source-mismatched
metadata returns only `INVALID_CATALOG`, without a partial inventory or fallback.
The host separately pins the serialized coverage export. Cancellation and retry
clear both projections; no persistent metadata cache is introduced.

The ledger byte pin is
`0466d16b2ec5809383b7d0bb40d068430bcd3890e06115f5be2cfffe46a4057e`;
the compact public coverage export pin is
`9609e1794c79681eb5b0558f2b0c8d965c732434bda841c2e3c9172902a2830d`.
These are integrity checks for historical metadata, not approval tokens.

The five-book collection uses the existing Atlas theme/layout, with book search,
status filtering and keyboard-accessible gap details. Original pilot titles,
stage IDs and prerequisites remain inspectable. Objectives are not supplied by
the vetted metadata and are explicitly unavailable, not inferred. Default
distribution gating stays OFF; no executable mission, grade, XP or mastered state
is added. #267/#261/#270 remain open. This is review evidence, not curriculum,
visual, release or learner-validation approval.

Main returns `{ok:true,result}` or `{ok:false,code}`; its redacted error vocabulary
is `UNAVAILABLE`, `RELEASE_DISABLED`, `INVALID_CATALOG`, `CANCELLED`, `TIMEOUT`,
`PROTOCOL_ERROR`, `START_FAILED`. Details, source paths and stderr never cross IPC.
The main-only UUID is not a learner/session identity and is removed from the
renderer result. Preview metadata is deliberately independent of profile changes.

The Java child uses the packaged runtime, no shell/PATH fallback, 64 MiB heap, 12s
host deadline, 4 KiB drained stderr budget, bounded stdout and UTF-8/duplicate-key
validation. It reads one request and exits. Success requires correct correlation,
no extra frame and exit code zero. Cancellation settles callers and escalates from
EOF to SIGTERM at 100ms and SIGKILL at 1s; new reads wait for old teardown. Java
verifies its parent PID, watches parent death and has a secondary 15s hard deadline.
Inherited Java injection variables are removed. Process limits are not a security
sandbox; no learner code or downloaded content is executed.

## Authority, integrity and privacy

- Pinned original-author input remains SHA-256
  `76377d04ecb0ff45690df32bbc001f66bfaa672e1935327e1c09b2b047a46f52`.
- Public export remains SHA-256
  `95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874`.
- Private checkpoint locator/source bodies are absent from this API. Source-section
  hash locators remain metadata, not independent source verification or approval.
- UNREVIEWED, zero approvals, JDK21_VERIFICATION_PENDING, empty mappings and all
  grading/import/XP/reward/mastery flags false are literal schema requirements.
- Four draft skills and six external prerequisites remain unmapped. Internal draft
  references are navigable, but never interpreted as approved SkillRefs.
- No SQLite connection, migration, learner snapshot, exposure, reward, estimate,
  adaptive recommendation or evaluation is invoked by the preview capability.
  The existing Ascension Tree, its exact graph and historical evidence stay separate.
- No new dependencies. Electron is patched from 44.9.0 to 44.9.3 for the
  high-severity certificate-trust advisory GHSA-jr5w-w6x2-84p9 discovered during
  this audit; the package and lockfile pin the patched runtime. The existing esbuild 0.27.3
  development-server advisory GHSA-g7r4-m6w7-qqqr remains a separate toolchain issue;
  packaged Electron serves local files and does not start a development server.

## UI and visual review

A dedicated navigation destination uses existing theme tokens, typography and
controls. Four selectable cards provide exact Java titles/IDs; the inspector
shows source locators, declared prerequisites, mapping gaps and stage IDs.
Search filters metadata locally. Predict/Debug/Build/Defend tabs support Left/Right,
Home/End, roving focus and labelled panels. Internal prerequisite links select
another draft; no link can call the mission workspace. Mission execution is
visibly disabled, with adjacent text identifying the missing fields. No success,
earned or unlock state exists. Prototype state controls route to the existing
prototype instead of simulating catalog data. No learner identity appears in the
preview provenance. The persistent shell can still identify a connected profile.

Desktop uses a two-column collection/inspector. At 760px cards form a two-column
collection above a full-width inspector. The focusable “Go to selected draft
details” shortcut avoids a long keyboard traversal. Theme/readability, wrapping,
no horizontal overflow, stage focus and reduced-motion behavior were inspected
in the actual packaged Linux app, offline with Chromium sandbox enabled:

| | Dark | Light |
| --- | --- | --- |
| 1280px | [Screenshot](screenshots/curriculum-1280-dark.png) | [Screenshot](screenshots/curriculum-1280-light.png) |
| 760px | [Screenshot](screenshots/curriculum-760-dark.png) | [Screenshot](screenshots/curriculum-760-light.png) |

These are full-page captures at the requested content-window widths; Chromium's
scrollbar occupies part of the PNG width. They show actual Java metadata, not mocks.
Native Windows/macOS execution and assistive-technology testing remain unverified.
Existing real-profile platform restrictions are unchanged.

The four gallery images were refreshed on 2026-11-03 from the actual packaged
five-book view, with Chromium sandboxing enabled, browser-offline mode and reduced
motion. They retain the original Atlas hero, theme tokens and draft inspector.
The new book cards and details show unknown mappings and learner validation,
reported zeros, historical inventory, first-pass notes and nonadditive revisions
without progress bars. This evidence is supplied for visual review, not approval.

## Adversarial verification and residual risks

Tests exercise strict framing, duplicate/invalid UTF-8, wrong versions/IDs,
extra private fields, spoofed approvals, changed metadata, oversized output,
missing/corrupt actual bundled catalogs, crashes, timeout, cancellation, stubborn
process teardown and stale generations. Packaged tests call the real registered
handlers with unauthorized senders/frames/arguments, verify disabled release,
reload/restart, late UI replies, malformed results, actual connected profile and
unchanged database/projection bytes. No real learner profile is used.

The finite serializer is reused from the CLI as a pure public method; file
publication is never called. No protected content is bundled into React static
assets. UI stage IDs have no execution handler. Review found no route from draft
metadata to challenge authorization, grading or earned progression. Successful
checks do not grant curriculum approval. This remains a trusted packaged runtime:
a malicious user replacing application binaries can bypass display restrictions;
the local launch flag is an audience gate, not DRM or a new security sandbox.
A pinned catalog change requires a separately reviewed version/schema/hash update.

## Reproduction and handoff

```sh
# Java 21: set JAVA_HOME to the test JDK (also used by Node test fixtures).
# Isolated databases; Xvfb required for the full JavaFX suite.
xvfb-run -a mvn -B -Dtest=CurriculumPreviewMainTest,KnowledgePreviewTest,KnowledgePreviewReadOnlyIntegrationTest,KnowledgePreviewAscensionBoundaryTest test
xvfb-run -a mvn -B test
cd desktop
npm ci
npm run build
npm run prepare:java
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e
# Packaged local review only (normal launch remains release-disabled):
./release/CodeFit-Gate0-linux-x64/codefit-gate0 --enable-curriculum-preview-local
```

Set `CODEFIT_CAPTURE_CURRICULUM_SCREENSHOTS=1` while running the packaged curriculum
E2E test to reproduce screenshots. The final draft PR records exact SHA, full-suite
counts and CI links. No public release or learning pilot is authorized or executed.

The five-book implementation was checked with Java 21 and Node 22. The existing
coverage-generator suite passed all 11 tests. The final Java regression passed
2,111 tests with no failures, errors or skips and Maven `BUILD SUCCESS`, after
75 targeted tests verified the final coverage labels and export pin. An initial
run lost generated build classes and was discarded; the subsequent full run also
passed all 2,111 tests. The final run's Xvfb wrapper returned exit 5 **after Maven
success**, because concurrent Playwright cleanup removed its temporary directory.
This is a harness cleanup limitation, not a silently ignored test failure.

The Linux host cannot create the stronger offline user/network namespace
(`unshare` fails while writing `uid_map`). This does not justify disabling the
Chromium sandbox. The focused packaged test instead exercises browser-offline
mode, bundled file assets and the real local Java reader. Native Windows/macOS
packaging and manual assistive-technology validation remain unverified.

The final real-Java Node suite passed all 98 tests, including malformed/missing/
stale/source-mismatched coverage, dual export pins and zero learner writes.
The eight focused packaged curriculum E2Es passed, including release OFF,
owning-frame authorization, cancellation/reload/restart, synthetic connected
learner byte/projection preservation, filters, keyboard details and responsive
light/dark captures. Browser-offline assertions observed no external HTTP(S)
requests, console/page errors or private sentinels.
The complete packaged Linux Electron suite passed all 95 tests with sandboxing
enabled. Unrelated screenshots regenerated by existing full-suite tests were
restored; only the four curriculum captures are changed. Build, Java preparation
and unsigned Linux packaging succeeded. Normal launch still denies preview reads.

Final diff review found no significant issues, and secret scanning found no
secrets in the changed files. CodeQL completed JavaScript analysis with zero
alerts, but timed out before every language completed; Java CodeQL coverage is
therefore unverified. Passing runtime tests or partial static analysis does not
grant release, curriculum or learner-validation approval.

## Future reviewed integration (not implemented)

1. Owner authorizes the intended distribution audience for the exact preview.
2. Independently enrolled technical and pedagogical reviewers evaluate this exact
   revision; #253's existing Items 19–25 packet does not approve these Items 57–60.
3. Owner adjudicates explicit versioned mappings to existing Java SkillRef/GraphRef
   definitions, resolves external prerequisites, and publishes a separately reviewed
   catalog version. Similar names and suffix stripping cannot establish equivalence.
4. Under #269, reviewed original briefs/rubrics and supported evaluator guarantees
   enter the existing challenge catalog, presentation authorization and durable
   evaluation pipeline. A stage ID alone must never authorize content or grading.
5. Actual observations flow through the existing evidence/mastery/reward owners.
   Neither draft display nor curriculum approval creates learner evidence.

Keep #261/#256 open. No Gate 3, new learner writes, full syllabus migration, approval
workflow, graders or independent progression engine is introduced here.
