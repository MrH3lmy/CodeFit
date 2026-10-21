# Gate 1 verification

Final packaged evidence and implementation commit are recorded below after the packaging run. Full Java regression already passed: **1,803 tests, zero failures/errors/skips**, Java 21, Xvfb, 2026-10-20. No Java source, policy, schema or JavaFX file changed for Gate 1.

The complete native suite retains all six Gate 0 security/process tests and adds nine visual/interaction tests. It explicitly sets Playwright `chromiumSandbox: true` and asserts the launch argv does not include `--no-sandbox`. Playwright’s default would otherwise disable Chromium’s process sandbox even when `BrowserWindow.webPreferences.sandbox` is true; both settings are now verified. No sandbox workaround is used.

## Reproduce

From `desktop/`, after `npm ci`, `npm run build`, and `npm run prepare:java`:

```sh
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  CODEFIT_CAPTURE_SCREENSHOTS=1 xvfb-run -a -s "-screen 0 2560x1800x24" npm run test:e2e
# Stronger Linux offline proof: only loopback exists for the local automation debugger.
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  CODEFIT_CAPTURE_SCREENSHOTS=1 xvfb-run -a -s "-screen 0 2560x1800x24" sh scripts/offline-linux.sh npm run test:e2e
# Native X11 recording; ffmpeg with x11grab and libvpx-vp9 support is needed.
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  CODEFIT_FFMPEG=/absolute/path/to/ffmpeg xvfb-run -a node scripts/record-walkthrough.mjs
```

The archive/executable and experimental configuration directory keep Gate 0 filenames to preserve the isolated packaging/lifecycle contract. The actual window title and contents identify the Gate 1 proposal. Native Linux test helpers use POSIX process inspection; do not assume these tests validate another OS.

## Covered behavior

- Twelve native screen/theme/width combinations, content provenance and no document horizontal overflow.
- Same real Java read, strict bridge keys, renderer Node absence, CSP/network/window restrictions and unauthorized IPC rejection.
- Cancellation, replacement session, Java crash/retry, renderer crash, ordinary window close, hard main crash and duplicate process handling; learner sentinel preserved.
- Search auto-selects the matching briefing before any explicit result click; filtering, bookmark state and blocked inspection.
- Arrow-key graph traversal, zoom/reset, prerequisite navigation, keyboard tab activation and evidence states.
- Editing, Escape guard, dirty navigation-to-tree guard, in-memory acknowledgment, loading/feedback/interrupted/unavailable specimens and focus restoration.
- Empty/loading/offline/error/unknown state recovery; unknown removes active/history/completion examples.
- Modal focus containment, theme toggle, minimum width, reduced-motion loading and an explicit narrow inspector action.
- Rapid Library leave/re-entry preserves a single isolated pending read.
- 2× device pixel ratio Electron rendering and representative text contrast across both token palettes.

## Accessibility and limitations

Keyboard semantics, visible focus, role/name structure and reduced-motion CSS are exercised in native Electron. This is not a completed manual screen-reader audit. SVG art is decorative; semantic node buttons carry the map information. Meaningful copy was enlarged after the first screenshot review, and weak metadata colors were corrected in both themes. Primary mission actions moved above longer objectives to stay reachable in the initial narrow view. Token contrast tests cover text/background pairs, not every blended edge, hover, disabled control or visual combination.

The prototype demonstrates interaction and truthful failure language, not a functional editor, runner, recovery system or learning integration. No Java compiler runs, no frozen learner submission is created, no durable save is acknowledged and no real XP/reward/skill state is computed. Sample changes reset; the isolated library is the only Java-backed screen. The conceptual seven-node graph is not the existing pilot graph. Windows/macOS rendering, screen readers, OS high-contrast modes, signing/installers and native lifecycle remain future work. No visual approval or feature-parity claim is made.
