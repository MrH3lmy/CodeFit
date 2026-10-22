# Learner snapshot verification

Baseline: latest main `78898a4a605768f582ef3a3f8835afbe1fb2f286`, merged PR #264. Dedicated worktree/branch; no unrelated active PR was used. Tests and screenshots use generated synthetic databases and disposable copies only. No real learner profile was opened or modified during development.

## Automated checks

| Check | Result |
| --- | --- |
| New focused Java snapshot/protocol tests | 12 passed, zero failures/errors/skips |
| New tests plus existing experimental application integration | 20 passed in the earlier focused run |
| Full Java21/JavaFX suite | 1,827 passed, zero failures/errors/skips (11m20s) |
| Full Node suite | 40 passed, zero failures/skips |
| Packaged offline Linux Electron suite | 29 passed (1.1 minutes), including the 21 retained tests |
| TypeScript/build | Passed; final build rechecked after test harness corrections |
| Visual verification | Eight actual packaged-app screenshots, dark/light 1280px/760px plus refusal/cancellation/missing-data states |

Java snapshot tests cover complete compatible sources, missing/invalid progress, older/future/incomplete versions, foreign/experimental files, source ownership, symlink/hard-link refusal, restoration to a new disposable file, frozen results after source updates, failed-capture preservation, active WAL state, interrupted rollback journal refusal, incomplete generations, size/retention bounds and repeated-session collision. Real subprocess tests validate session authorization, write-method rejection and restart. All connections to source data are read-only.

Node tests include the actual bundled Java reader, coalesced concurrent requests, strict framing/version/identity/private-field rejection, deadline and cancellation escalation, lazy runtime resolution and stale-session replacement. The test-only Java fixture is compiled into an OS temporary directory with the host Java21 JDK; it is never part of the packaged engine or production IPC.

Electron tests drive the actual FXML-free React/Electron application, sandbox and private Java process. Native dialog responses are controlled in the test main process to select generated fixtures; production has no fixture/fault-selection flag. Tests verify explicit confirmation content/default cancellation behavior, source-byte and unrelated learner-sentinel preservation, precise read values, hidden-field redaction, unauthorized frame/path rejection, cancelled switching, reload/restart with fresh consent, private backup preservation, incompatible schema, Java crash, delayed consent and narrow unavailable-data rendering. The complete retained UI/editor/library recovery suite also passes.

## Reproduction

Use Java21, Node24 and a functioning X11/Chromium sandbox. Local commands in this environment:

```sh
DISPLAY=:105 /workspace/run216 test
cd desktop
export JAVA_HOME=/workspace/tools/jdk-21.0.12.1+1
npm ci
npm run build
CODEFIT_MAVEN=/workspace/run216 npm run prepare:java
npm test
NODE_USE_ENV_PROXY=1 NODE_USE_SYSTEM_CA=1 npm run package
DISPLAY=:105 CODEFIT_CAPTURE_PROFILE_SCREENSHOTS=1 \
  CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e
```

`/workspace/run216` is the environment's Maven/Java21/proxy/CA wrapper; use ordinary Maven with equivalent settings elsewhere. Do not run Java builds and `prepare:java` concurrently in one target directory. The offline script permits loopback only for the test debugger and removes external network routes. Chromium sandbox is explicitly enabled; no `--no-sandbox` workaround is used. Existing `IsolatedDatabaseExtension` and workflow checks remain intact.

## Independent review and mechanism probes

Independent GPT-6 Astra probes used the shipped sqlite-jdbc **3.46.1.0** and synthetic `/tmp` databases:

- A pinned read transaction backed up value 1 even after a separate WAL writer committed value 2.
- `mode=ro` changed existing WAL shared-memory bytes, confirming the required consent caveat.
- A hot rollback journal caused `SQLITE_READONLY_ROLLBACK`; source DB/journal hashes remained unchanged.
- Throwing on every backup progress callback did **not** abort copying; neither did `sqlite3_interrupt()`. Native copying completed before the observer exception surfaced. The implementation therefore uses supervised process termination, a separate Java hard deadline and size bounds.

The Astra implementation agent subsequently exhausted its usage quota. Final independent application review used the available model, while retaining those Astra mechanism findings; a complete post-implementation Astra application review is not claimed. Review covered source mutation/migration, backup consistency/publication, secrets/permissions, IPC authorization, stale profile/session state, reload/cancellation and demonstration provenance. No remaining confirmed production blocker was found.

Confirmed findings corrected during implementation: eager runtime lookup could crash the shell; cancelled selection could leave a hidden old reader; a repeated capture UUID could replace a completed backup; a duplicate error key blocked TypeScript compilation. Test-only issues corrected were a native-dialog overload stub and an outdated button selector. Final visual review also corrected a message that claimed a backup already existed before successful consent/capture; the decline test now guards that wording. Assertions were retained or strengthened.

## Explicit limits

Only the named local Linux filesystems are enabled. Windows/macOS, network/FUSE filesystems, device power loss and hostile same-user directory replacement are not verified. Cooperative locks do not exclude legacy/external tools; source reads never grant a writer capability. Snapshot files contain sensitive full-profile data and have a bounded manual-archive retention policy. Existing learner writes, source migration/restore, mastery/adaptation/exposure/runner/draft/P2P endpoints remain unavailable. This slice does not close #261 or start Gate 3.
