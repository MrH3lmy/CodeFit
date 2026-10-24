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
      v: 1; kind: "author-draft-curriculum-preview";
      source: "bundled-author-metadata"; learnerBound: false;
      preview: PublicPreviewV1;
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
