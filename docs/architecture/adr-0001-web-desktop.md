# ADR 0001 — Experimental web desktop with a Java-owned read boundary

Status: Gate 0 recommendation, pending review. References: #256, #257, #259, #261, #258, #260. Draft #255 stays unmerged and is not the design baseline.

## Decision

Use Electron to supervise the preserved Java 21 engine and render locally bundled React/TypeScript/Vite assets. A sandboxed, context-isolated preload exposes exactly two operations: list deck descriptors and cancel that read. Electron main validates the owning main frame and schema; a private stdio pipe carries versioned bounded JSON to Java. Java validates again. No HTTP server, renderer Node access, SQL, file paths, general command dispatcher, or learning policy in TypeScript.

The Gate 0 app is explicitly an isolated demonstration. Java creates a fresh disposable SQLite database with the existing initializer, closes every writer, then reopens with `mode=ro&immutable=1`. It invokes the existing `DeckService.getDecks()` and returns only id, name, description. It never accepts a learner database path. The UI identifies its sample source; seeded decks are real Java service results, not renderer fixtures. The initialization writes only to a Java-created temporary directory. `immutable` is safe only for this closed disposable snapshot, never a live learner database or WAL.

## Why this scope

The existing application's startup performs migrations/seeding and starts evidence backfill. Several apparently read-like use cases also reconcile rewards or create adaptive decisions. Calling those to populate a dashboard would violate a read-only spike. Deck descriptors need no challenge exposure and avoid reference answers, test cases, reserved content, identity, evidence and learner metrics. The complete headless use-case and authorization extraction belongs to #261; this proof must not imply it is already done.

## Electron and Tauri

Both can host the desired React/SVG UI and supervise a Java sidecar. Both still need a platform-specific Java runtime, SQLite native library and release signing. Tauri 2 reduces desktop-host distribution size by using OS webviews, but does not remove the Java runtime or native packaging matrix. Its Rust capabilities and sidecar allowlist are attractive; adding Rust while preserving the Java engine introduces a third production language. Windows WebView2 availability/offline provisioning and macOS WKWebView variation add fidelity and offline validation obligations. Electron ships a consistent Chromium target, supports narrowly scoped IPC and Node supervision directly, and offers mature desktop automation. Its larger disk/RSS footprint, Chromium patch cadence and privileged main process are material costs.

Recommend Electron for Gate 0 and the visual approval prototype, conditional on measured artifact/lifecycle results and an explicit acceptance of resource cost. Do not invent a Tauri benchmark: report actual measurements and missing platform/toolchain evidence separately in the verification report. Revisit if distribution/RSS budgets fail; React and the Java wire boundary remain replaceable independently.

### Measured Linux proof and unmeasured alternative

| Criterion | Electron Gate 0 | Tauri 2 assessment |
| --- | --- | --- |
| Java 21 integration | Proven bundled `jlink` runtime, fixed private stdio child, real service/SQLite read | Feasible Rust sidecar + capability allowlist; not built here |
| Offline local artifact | Packaged x86-64 Linux executable passed six native tests in a namespace with only loopback and no external route | OS webview/runtime provisioning must be demonstrated, especially Windows WebView2 offline installation |
| Rendering | Real Chromium desktop screenshots, dark/light at 1280 and 760 px; sandbox enabled | React/SVG feasible; WKWebView/WebView2/WebKitGTK variation requires native fidelity/interaction tests |
| Unpacked artifact | Approximately 432 MiB, including ~89 MiB Java runtime and ~45 MiB existing engine/dependencies | No measured artifact; a smaller host does not remove the Java/engine payload |
| Fresh app startup | Five fresh-profile/process launches: 1.30–1.84 s, median 1.79 s to first real Java result; warm OS cache, not cold boot | Not measured; do not infer speed from download size |
| Memory | Seven processes; summed RSS ~661–668 MiB including Java ~65–70 MiB, shared pages counted repeatedly; not PSS/peak | Not measured; webview and JVM still consume memory |
| Packaging | Unsigned Linux local artifact proven; native macOS signing/notarization and Windows launcher/installer validation outstanding | Rust + native webview dev/runtime requirements, sidecar bundling/signing still necessary |
| Maintenance/security | Chromium/Electron patch cadence, larger host, privileged main; narrow preload, context isolation, sandbox and no remote content | Rust capability model narrows host API; OS webview patch cadence/availability varies; adds Rust alongside Java/TypeScript |

Raw methodology/results are in [verification](gate0/verification.md) and [measurements](gate0/measurements.json). The host lacks Rust/Cargo and WebKitGTK development/runtime support; no Tauri launch or benchmark is claimed. This ADR recommends continuing the **reviewable Electron experiment**, not ratifying a final production host. Before ratification, obtain native Windows/macOS evidence and either a comparable Tauri build/measurement or explicit reviewer acceptance of the unmeasured tradeoff. No installer-size comparison is claimed from an unsigned local tarball.

## Lifecycle and failure policy

One experimental instance per dedicated Electron user-data directory. No learner profile selection. Main spawns a fixed bundled Java executable and classpath without a shell. Startup has a deadline; one request is allowed at a time, each with a deadline and correlation UUID. Invalid/oversized/unexpected responses fail the entire pipe closed. Logs are bounded stderr diagnostics with no content payloads. Cancellation closes the whole disposable read-only session; the next read creates a fresh session. No mutating command is replayed. EOF, normal close, renderer crash, main-process loss and escalation must terminate the child; Java monitors parent liveness and cleans its own temporary directory. Hard OS kills can leave disposable temporary files, never learner writes. This is not a future transaction/recovery policy for learner commands.

## Packaging and rollback

Build with an explicit Java 21 JDK and `jlink` a platform runtime. Package assets, runtime, engine classes/resources and runtime dependencies together. Locate Java relative to Electron resources; never download a runtime or use PATH on application launch. Packaging must be performed/tested per OS/architecture; Linux proof does not establish Windows/macOS readiness. Code execution later requires `jdk.compiler` and a separately audited runner packaging decision.

The JavaFX entrypoint, production database default, migrations, policies and test isolation extension remain unchanged. Rollback is closing/removing the experimental artifact and its dedicated Chromium data directory. No data migration or profile restore is needed. A later writable cutover requires explicit ownership locks, backup/upgrade compatibility and crash recovery tests before connecting a learner profile.
