# Gate2 verification

Verified on 2026-10-22 in a dedicated worktree from `c7b3f8cc9867c28a6c918321fa759515c0379973` (Gate1 PR #263). Linux x86-64, Node 24/npm11, Electron 44.9.0, Playwright 1.64.1, Temurin Java 21.0.12.1. No real learner profile was selected or modified. The unchanged `IsolatedDatabaseExtension` fingerprints the default database around Java tests; Electron tests create and verify an isolated learner sentinel.

## Results

| Check | Result |
|---|---|
| TypeScript + production Vite/preload/main build | Passed |
| Focused Java v2 protocol/application and retained Gate0 suites | 33 passed |
| Complete Java unit/integration/JavaFX suite | 1,815 passed, 0 failures/errors/skips (9 minutes 18 seconds) |
| Node session, ownership, journal and application suite | 29 passed, 0 failed/skipped |
| Packaged Linux Electron E2E in external-network-free namespace | 21 passed, 0 failed/skipped (44.3 seconds) |
| Approved Gate1 visual/interaction/accessibility cases | All 9 retained E2E cases passed, including 12 screen/theme/width compositions |
| Screenshots | 12 actual packaged-app PNGs; final SHA-256 manifest in `evidence.json` |
| Linux process measurements | Five fresh isolated profiles, sandbox enabled; `measurements.json` |
| Whitespace/patch validation | `git diff --check` passed |

The full Java run was independently executed against this worktree after the final Java changes. Do not infer Windows/macOS certification, physical power-loss testing, or full web feature parity from these results.

## Reproduction

From the repository root, use Java21 and an X11 display:

```sh
DISPLAY=:103 /workspace/run216 test
cd desktop
npm ci
npm run build
JAVA_HOME=/workspace/tools/jdk-21.0.12.1+1 CODEFIT_MAVEN=/workspace/run216 npm run prepare:java
npm test
NODE_USE_ENV_PROXY=1 NODE_USE_SYSTEM_CA=1 npm run package
DISPLAY=:103 CODEFIT_CAPTURE_GATE2_SCREENSHOTS=1 \
  CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e
DISPLAY=:103 CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh node scripts/measure.mjs
```

`/workspace/run216` is this environment's Java21/Maven trust-store wrapper. On another host use Maven with the equivalent JDK/proxy/CA configuration; the generic commands are in the desktop README. Do not run Java builds and `prepare:java` concurrently against the same Maven target directory. The full Java run used Xvfb `:102`; the final desktop run used a fresh `:103` after the earlier display process stopped. Stale display locks were left untouched, and no sandbox-disabling workaround was used.

The offline script creates a network namespace with loopback only for Playwright's local debugger connection. The app does not open a runtime HTTP service. Tests explicitly enable Chromium sandboxing and assert that `--no-sandbox` is absent. The artifact loads local assets and its bundled runtime even with invalid inherited `JAVA_HOME`, `CLASSPATH` and Java injection variables.

## Evidence by invariant

- **No-write query:** whole-database byte equality across state and receipt lookups; attempted `user_progress`/daily-quest writes fail on the read connection. Unsupported progression is explicit, and no answer/content fields serialize.
- **Atomic create/receipt:** ledger failure rolls back the inserted deck; a subprocess killed with both rows uncommitted leaves neither after startup recovery. Successful and terminal rejected receipts replay durably after restart. Changed payload under the same ID conflicts.
- **Unknown completion:** real Java commits a deck, the session wrapper drops the response, and a restarted main application recovers the same receipt. Main's journal exists before dispatch, status lookups do not mutate it, and explicit recovery preserves exact command identity.
- **Ownership:** second real Java process receives `PROFILE_BUSY`; current JavaFX startup fails before its initializer when headless owns that file. Symlink/hard-link profile forms and foreign/schema-version files fail closed. Electron's second instance exits without another Java engine.
- **Lifecycle:** Java/renderer/main crashes, normal close, startup/request timeouts, cancellation, EOF/SIGTERM/SIGKILL escalation and stale-response suppression. Cancellation after a committed but withheld reply reconciles the receipt; releasing the late reply cannot replace the recovered UI state.
- **Protocol/security:** strict type/version/field/UUID/frame bounds, malformed and non-UTF8 JSON, BOM, duplicate keys, extra private fields, wrong result kind/profile/command correlation, frame authorization and invalid write arguments. Main exposes exactly six frozen preload methods and no Node APIs.
- **UI regression:** all approved preview screens and their navigation, editor guards, evidence states, keyboard/dialog behavior, theme, reduced motion, HiDPI and representative contrast checks remain covered. New library captures show both themes at 1280px/760px, with enabled terminal acknowledgement after theme transitions settle. A real 256-character name / 2,048-character Unicode description remains readable without horizontal overflow at 760px.

Test-only fault wrappers intercept a real response inside the Playwright/Node harness; no such hook is shipped in the production boundary. Screenshots contain only deterministic test input and an isolated experimental profile.

## Measurements and limits

Five packaged launches reached the first real library result in 2.15–3.10 seconds in this shared environment (including UI navigation and fresh-profile bootstrap). Summed process RSS was about 697–719 MiB; Java was about 66–71 MiB. These are warm-cache, shared-host, automated observations, not performance thresholds or peak/PSS measurements. Parallel Java/E2E activity can affect them. See raw samples in `measurements.json`.

The profile lock is cooperative and requires current participating launchers. Existing learner profiles are intentionally unavailable. Linux atomic rename/fsync plus SQLite FULL synchronous commits are implemented; device-level power-loss behavior and native Windows/macOS durability remain unverified. Those platforms still require their own runtime, signing, installer and lifecycle validation under later gates.
