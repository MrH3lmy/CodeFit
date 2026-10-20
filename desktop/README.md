# CodeFit Gate 0 desktop proof

Experimental React/TypeScript/Vite + Electron frontend for the existing Java 21 engine. This app opens **only a newly created disposable sample database**, queries the real `DeckService`, then serves deck descriptors through a private read-only pipe. It never opens the learner profile. JavaFX remains the normal application.

See [ADR](../docs/architecture/adr-0001-web-desktop.md), [ownership map](../docs/architecture/gate0/dependency-map.md), [threat model](../docs/architecture/gate0/security.md), [verification](../docs/architecture/gate0/verification.md) and [Gate 1 approval plan](../docs/architecture/gate1-visual-approval.md). This is #257 / #256 only; neither this screen nor draft #255 constitutes approval of the final product design.

## Build and run

Verified on Debian 13 x86-64 with Node 24, npm 11, Maven 3.9, Temurin Java 21 and an X11 display. Initial dependency acquisition needs network access; the resulting app does not. Use a fresh checkout/worktree. `JAVA_HOME` must identify a **JDK 21 for the target platform**, including `jlink`.

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
cd desktop
npm ci
npm run build
npm run prepare:java
npm test
npm start
```

`CODEFIT_MAVEN` may be an absolute executable wrapper for Maven/proxy settings; it is never evaluated as a shell command. On a managed proxy, Node 24's `NODE_USE_ENV_PROXY=1 NODE_USE_SYSTEM_CA=1` can be used for dependency acquisition without bypassing TLS checks. No runtime downloads occur on app launch.

The renderer is always loaded from the production Vite build, including during `npm start`. No development HTTP server or remote content is used. Changes require another `npm run build`. The preload exposes only `listDecks()` and `cancelRead()`; cancellation closes the disposable session and a subsequent refresh starts another.

## Package and test the actual artifact

```sh
npm run package
./release/CodeFit-Gate0-linux-x64/codefit-gate0
# Under a display, or prefix with xvfb-run -a on a host providing Xvfb:
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" npm run test:e2e

# Linux optional stronger offline check; requires user/network namespaces and iproute2:
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e

# Same packaged process-tree measurements as the verification report:
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh node scripts/measure.mjs
```

Do not add `--no-sandbox` to work around host restrictions. Use a host with a functioning Chromium sandbox. The native e2e/process-fault tests currently target Linux (`ps`, `/proc`, POSIX signals); Windows/macOS tests belong to their native packaging validation. Playwright opens local debugging connections only in the test harness; normal app startup has no debugging listener or HTTP server.

`CODEFIT_CAPTURE_SCREENSHOTS=1` captures the four real desktop views into `docs/architecture/gate0/screenshots`. Tests use isolated Chromium configuration directories and a sentinel `codefit.db`; they do not modify a real learner database. `npm test` includes the real bundled Java service, so run `prepare:java` first.

From the repository root, retain the complete Java regression run:

```sh
xvfb-run -a mvn -B test
```

The new **Desktop Gate 0** workflow builds and tests the packaged Linux artifact without disabling its sandbox. It publishes an unsigned tarball for seven days, preserving executable permissions. The existing Java CI workflow is unchanged.

## Layout, lifecycle and rollback

- `src/`: React view and strict public DTO schemas; no learning policies or database access.
- `electron/`: fixed bundled process path, session ownership, exact-frame IPC authorization and sandboxed preload.
- `../src/main/java/com/codefit/desktop/`: separate headless entrypoint and bounded Java protocol, reusing the existing initializer and deck service.
- `java/`: generated platform runtime + existing compiled engine/resources/dependencies. Never commit this directory.
- `release/`: generated unsigned local artifact. Package paths are resolved from Electron resources, not the working directory or `JAVA_HOME`.

Chromium uses its dedicated `CodeFit-Gate0` directory under the platform application-data directory; on Linux this is normally `~/.config/CodeFit-Gate0`. The ephemeral browser session never loads the JavaFX profile/preferences. Java creates `codefit-gate0-*` directories under its OS temp directory and deletes its own known SQLite files on graceful close. SIGKILL of Java or power loss can leave disposable sample files. No automatic recursive sweep is performed.

Rollback: close the proof, remove its artifact/generated build directories and, if desired, its dedicated Chromium configuration directory. After confirming no proof process remains, abandoned `codefit-gate0-*` sample directories can be removed. Do not delete any JavaFX data or general `codefit.db`. There is no learner migration to reverse.

## Platform limits

The Linux local artifact is proven; it is not a signed installer. Build separately on each target OS/architecture. Windows still needs a native Maven launcher path (Node cannot directly spawn `mvn.cmd` without a command interpreter), correct SQLite natives, installer/runtime layout and shutdown verification. macOS needs a native build, hardened signing of nested Java/Electron binaries and notarization. No cross-platform readiness claim is made. The reduced runtime supports this read use case; it intentionally lacks `jdk.compiler` and cannot replace the existing coding runner runtime.
