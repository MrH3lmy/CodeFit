# CodeFit · Gate2 experimental Java library

The approved React Command Center, Ascension Tree and Mission Console remain labeled Gate1 previews. The **Java Library** now reads authoritative deck descriptors from a durable, isolated experimental profile and creates a deck through the existing Java service. Java owns SQLite and validates every command. No real learner profile is opened, migrated or opted in; JavaFX remains the normal application.

Start with the [Gate2 review walkthrough and screenshots](../docs/architecture/gate2/README.md), [ADR 0002](../docs/architecture/adr-0002-headless-library-boundary.md), [exact API and ownership map](../docs/architecture/gate2/api.md), and [verification evidence](../docs/architecture/gate2/verification.md). This is #261 under #256, based on visually approved/merged Gate1 PR #263. The [Gate1 gallery](../docs/architecture/gate1/README.md) remains the visual contract. Draft #255 is not the baseline.

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

The renderer is always loaded from the production Vite build, including during `npm start`. No development HTTP server or remote content is used. Changes require another `npm run build`. The preload exposes only `readState`, `createDeck`, `pendingDeck`, `recoverDeck`, `acknowledgeDeck`, and `cancelOperation`. Cancellation stops the connection while retaining the durable operation; it does not promise a write rollback.

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

`CODEFIT_CAPTURE_SCREENSHOTS=1` captures the twelve primary Gate 1 views plus interaction/state evidence into `docs/architecture/gate1/screenshots`. `CODEFIT_CAPTURE_GATE2_SCREENSHOTS=1` captures the experimental library in both themes/widths plus confirmed, pending, rejection, unknown and reconciled states into `docs/architecture/gate2/screenshots`. Tests use isolated Chromium configuration directories and a sentinel `codefit.db`; they do not modify a real learner database. `npm test` includes the real bundled Java service, so run `prepare:java` first.

From the repository root, retain the complete Java regression run:

```sh
xvfb-run -a mvn -B test
```

The **Desktop Gate 0** workflow builds and tests the packaged Linux artifact with explicit `chromiumSandbox: true` in the Playwright harness and an argv assertion against `--no-sandbox`. It publishes an unsigned tarball for seven days, preserving executable permissions. The existing Java CI workflow is unchanged.

## Layout, lifecycle and rollback

- `src/`: reusable visual components, three screen modules, sample mission dialog, labeled fixtures and the isolated Java Library; no learning policies or database access.
- `electron/`: fixed bundled process path, session ownership, exact-frame IPC authorization and sandboxed preload.
- `../src/main/java/com/codefit/desktop/`: separate headless entrypoint and bounded Java protocol, separating new-profile bootstrap, strictly read-only queries and transactional deck creation through the existing Java service.
- `java/`: generated platform runtime + existing compiled engine/resources/dependencies. Never commit this directory.
- `release/`: generated unsigned local artifact. Package paths are resolved from Electron resources, not the working directory or `JAVA_HOME`.

Chromium keeps the existing independent `CodeFit-Gate0` user-data directory under platform application data (normally `~/.config/CodeFit-Gate0` on Linux). The fixed `experimental-library-v1` subdirectory holds the experimental database, marker and Java ownership lock. `experimental-operations-v1` holds the single current creation intent. Both persist across restart. The renderer cannot select a path. The browser session remains ephemeral and does not load JavaFX preferences.

On the first Java Library operation, Java initializes a fresh staged database with starter decks and publishes it atomically. Existing profiles are verified and recovered under the owner lock; they are never automatically migrated or replaced. Named state/receipt queries use read-only SQLite connections. JavaFX now acquires the same cooperative profile lock before its initializer and retains it until JVM exit. Older binaries and external SQLite tools do not honor this lock; do not point them at the experimental profile while it is open.

Recovery: after an interrupted response, reopen Java Library. A saved receipt confirms the actual outcome. If completion remains unknown, **Check and finish saved creation** checks Java and replays the same saved command only when needed. **Acknowledge and continue** clears a terminal receipt from the main journal after Java verifies it. A profile identity mismatch or invalid journal fails closed and keeps the files for review.

Rollback: close every experimental process and keep the two experimental subdirectories together if you want to preserve your experimental decks and command recovery. Removing the artifact requires no learner migration rollback. These directories may be archived or deliberately removed only after their experimental data is no longer needed; no application cleanup recursively deletes them. Do not delete any ordinary JavaFX `codefit.db` or learner profile. A future learner opt-in, migration or backup workflow requires separate review.

## Platform limits

The Linux local artifact is proven; it is not a signed installer. Build separately on each target OS/architecture. Windows still needs a native Maven launcher path (Node cannot directly spawn `mvn.cmd` without a command interpreter), correct SQLite natives, installer/runtime layout and shutdown verification. macOS needs a native build, hardened signing of nested Java/Electron binaries and notarization. No cross-platform readiness claim is made. The reduced runtime supports this library read/create slice; it intentionally lacks `jdk.compiler` and cannot replace the existing coding runner runtime.
