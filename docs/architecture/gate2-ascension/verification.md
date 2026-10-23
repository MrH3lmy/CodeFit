# Verification and adversarial review

Baseline: `febc92aad4a486b72dfb836e9a1ad7ca0487b9eb` (merged PR #265).
Branch: `feat/gate2-ascension-evidence`. Scope: read-only graph and historical
estimates, not current eligibility, learner writes or Gate 3.

## Reproduction

Use Java 21, Maven 3.9, Node 24, an X11 display and a functioning Chromium
sandbox. All fixtures use temporary SQLite files. No real learner profile was
opened. `IsolatedDatabaseExtension` and the JavaFX regression suite are unchanged.

```sh
# Repository root; xvfb-run may be replaced by an existing DISPLAY.
xvfb-run -a mvn -B -Dtest=LearnerSkillProjectionTest,LearnerProtocolTest test
xvfb-run -a mvn -B test
cd desktop
npm ci
npm run build
npm run prepare:java
npm test
npm run package
CODEFIT_CAPTURE_ASCENSION_SCREENSHOTS=1 \
  CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e
```

`JAVA_HOME` must point to a host JDK 21 for test fixture compilation. The packaged
app uses its bundled Java runtime even when its launch environment has an invalid
`JAVA_HOME`. The Linux offline wrapper runs with the network namespace isolated;
the app retains Chromium's sandbox. No `--no-sandbox` workaround was used.

## Local results (2026-10-23)

- Complete Java 21/Xvfb Maven suite: **1,847 passed**, zero failures, errors or skips (11m54s).
- Focused projection/protocol classes: **23 passed**, zero failures, errors or skips.
- Complete Node suite: **46 passed**, zero failures or skips.
- Complete packaged Electron suite, network isolated: **40 passed** (2m36s).
- TypeScript/Vite build, Java runtime preparation and Linux packaging: passed.
- Twelve final screenshot hashes and `git diff --check`: passed.
- GitHub CI runs and final commit identity are recorded in the draft PR; this document records local verification.

## Coverage and findings

| Risk | Verification and result |
| --- | --- |
| Historical support appears current | Every reply has `currentSupport: NOT_EVALUATED`. UI scopes band, support, freshness, reasons and counts to the original estimate time and shows capture time separately. Old `CURRENT` never becomes a current claim. |
| Withdrawn evidence unlocks a node | Retraction, revision, missing head, mapping and epoch fixtures attach warnings; nodes remain inspectable, never earned/unlocked. Header absence is explicitly not source validation. |
| Wrong graph or positive fallback | Exact graph/skill definitions, full definition equality, checksum and canonical saved inputs are verified. Latest mismatched/corrupt/future/unsupported revision is unavailable, without older positive fallback. Newer published graph is disclosed without mixing edges. |
| Hidden content crosses IPC | Explicit Java serializers plus strict Zod schemas admit only definitions, finite codes and counts. Private check/outcome/reflection sentinels do not appear in replies or the DOM. No raw source, payload, submission, answer or P2P key fields exist in this contract. |
| Opening the tree writes learner data | Projection tests compare complete source and backup bytes before/after repeated reads, assert query-only enforcement and unchanged global DatabaseConfig. No reward/estimate/adaptive/exposure owner is invoked. Real evaluation integration confirms reading a persisted revision creates no new revision. |
| Mixed capture or learner identities | One caller-owned transaction reads graph, saved inputs and lifecycle headers from the private capture. Source changes after capture cannot alter it. Delayed previous-profile replies, cancellation, queued reads, renderer reload and process restart are tested at transport and packaged UI boundaries. |
| Resource exhaustion | SQL row/text/blob guards, 32-node/128-edge topology limits, 1 MiB individual and 8 MiB aggregate saved inputs, stricter decoded collection limits, 256 KiB output framing and process timeout bound the read. Missing tables, views, oversized blobs/graphs and malformed wire graphs fail closed. No partial positive result is published. |
| Visual or keyboard regression | Actual packaged screenshots cover both themes at 1280/760; spatial arrows, Home/End, zoom, prerequisite selection, reduced-motion scrolling and 32-node scrolling are tested. Visual inspection found and fixed the narrow exact-reference column; a geometry regression now requires readable width. |
| Preview mistaken for learner data | Connected tree and Command Center label frozen learner data. Mission Console remains explicitly demonstration content. A disconnected tree remains a labeled preview; no auto-reconnect occurs. |

The test-only `AscensionFixture` writes canonical synthetic saved inputs for
precise historical policy cases; these are not claims that a learner executed
those challenges. A separate Java integration test uses the real presentation,
durable evaluation, evidence and estimate pipeline with packaged reference
solutions and compares the projected saved revision. Fixture builders are compiled
outside the release artifact. Screenshots use synthetic learner **Ada Learner**.

During review, queued-read cancellation was tightened so a closed session cannot
start a sidecar from an already queued request. Tests cover both queued and active
cancellation. The final source review found no remaining release-blocking defect
within this historical-only scope; independent PR review is still required.

## Packaged visual evidence

These are captures from the actual packaged Electron application, not browser
mockups. The source databases are synthetic, and the Java sidecar supplies all
connected graph data. Full-page captures include content below the 900px window.
The [capture manifest](evidence.json) records image hashes and dimensions.

| View | Dark | Light |
| --- | --- | --- |
| Tree, 1280px window | [Desktop](screenshots/tree-dark-1280.png) | [Desktop](screenshots/tree-light-1280.png) |
| Tree, 760px window | [Narrow](screenshots/tree-dark-760.png) | [Narrow](screenshots/tree-light-760.png) |
| Historical evidence, 1280px | [Desktop](screenshots/evidence-dark-1280.png) | [Desktop](screenshots/evidence-light-1280.png) |
| Historical evidence, 760px | [Narrow](screenshots/evidence-dark-760.png) | [Narrow](screenshots/evidence-light-760.png) |

Additional cases: [new learner](screenshots/new.png),
[stale interpretation](screenshots/stale.png),
[contradiction](screenshots/contradictory.png),
[withdrawal](screenshots/withdrawn.png).

Visual review checked provenance, legibility, selection, prerequisite direction,
inspector content, both theme palettes, narrow wrapping and absence of fabricated
completion badges. The four authored pilot skills form a chain; the UI preserves
that real topology rather than inventing branches to mimic the preview.

## Limits and follow-up

- Only the exact published pilot graph is exposed. Larger/missing/incompatible
  catalogs are unavailable; they are not silently truncated or replaced.
- Saved estimates are historical. Current evidence eligibility and actionable
  adaptive recommendations need a separate bounded read contract and review.
- Full snapshots remain unencrypted and may contain private artifacts and keys
  even though this projection does not expose them. File permissions do not
  protect against same-user processes or administrators. No automatic deletion,
  archival encryption or secure erasure is claimed; see the cleanup guidance.
- Native Windows/macOS profile access remains disabled. Native filesystem,
  permissions, ownership, capture, packaging and cancellation validation is still
  required. Linux Xvfb does not validate native screen readers or OS scaling.
- Power-loss durability and malicious same-user processes are outside this read
  slice's new verification. Existing capture limits and cooperative ownership
  constraints remain as documented in ADR 0003.
- JavaFX, evaluator/drafts, P2P, XP and recommendation policies are unchanged. Keep
  #261 open and stop for independent review before further migration.
