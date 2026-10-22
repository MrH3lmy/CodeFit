# ADR 0002: owned Java application boundary and recoverable experimental library

Status: implemented for Gate2 review; draft PR, no learner-profile opt-in or cutover approval.

Context: [#261](https://github.com/MrH3lmy/CodeFit/issues/261), its [complete kickoff](https://github.com/MrH3lmy/CodeFit/issues/261#issuecomment-6315126529), and [#256](https://github.com/MrH3lmy/CodeFit/issues/256). Baseline is `c7b3f8cc9867c28a6c918321fa759515c0379973`, approved Gate1 PR #263. [ADR 0001](adr-0001-web-desktop.md) remains the Electron/React/Java decision. This ADR replaces its disposable v1 read boundary for the Java Library only. Gate0 classes/tests remain as historical isolation coverage.

## Audited slice

`DeckService.createDeck(name, description)` validates a nonblank name, trims it, supplies `Custom training deck.` for a blank description, then calls `DeckRepository.save`. The repository inserts one deck; `decks.name` is UNIQUE. Neither method grants rewards, updates evidence/mastery, exposes challenge content, starts a runner, or performs peer work. A connection-aware overload reuses that same Java service validation and repository insert in a caller-owned transaction. Existing JavaFX callers retain their original overloads.

`DeckRepository.findPage` is a new stable-ID, bounded descriptor projection. It returns at most nine rows to produce an eight-row page and a next cursor. The SQL bounds strings before JDBC materializes them; overlong fields become an invalid projection, never a misleading truncated value. No card bodies, answers, assessment records or evidence are selected.

`PlayerSystemService.overview()` reconciles adaptive/reward state. `DailyQuestService.getActiveQuest()` may create state. Neither is a read-only query and neither is called here. Progression is explicitly `{status:"unsupported"}`. The three approved preview screens continue to show labeled fixtures.

## Profile and ownership decision

The application has one fixed, durable experimental profile under Electron's existing independent user-data directory:

- `CodeFit-Gate0/experimental-library-v1/codefit.db`, its ownership lock and exact experimental marker.
- `CodeFit-Gate0/experimental-operations-v1/pending-deck.json`, containing at most one current creation intent.

The profile is created on the first Java Library operation. It initially contains CodeFit's starter decks; subsequent descriptors and creation receipts come from this actual local database. It is separate from the normal learner profile. Renderer callers cannot choose a path. There is no import, migration, environment-variable override or real learner opt-in flow.

Java acquires a canonical database-path OS file lock before initialization or access and holds it through process work. The current JavaFX startup acquires the same lock before `DatabaseConfig.initialize()` or background tasks. JavaFX retains its lock until JVM exit: its bounded worker-shutdown waits do not prove that every writer has drained. Headless Java is single-threaded for application requests; its close releases the lock only after the request loop exits. Parent loss halts the child, allowing the OS to release handles and SQLite to recover unfinished transactions. Lock files are never unlinked during release.

Electron also uses its existing single-instance lock and serializes sidecar replacement until the previous process exits. Both layers are needed: the Java lock covers direct launches and JavaFX. This is cooperative ownership; older binaries and external SQLite tools do not honor it. That limitation is another reason this gate cannot open a learner profile. Canonical path aliases share a lock; profile symlinks and database hard links fail closed.

## Bootstrap, compatibility and recovery

Bootstrap is an explicit startup operation, not part of `state.read`. Only a new empty directory or a correctly marked interrupted bootstrap is accepted. The existing initializer/migrations run against unpublished `bootstrap.db`; Java adds profile UUID/version metadata and the command ledger, closes all connections, syncs the staging file and atomically publishes `codefit.db`. Interrupted staging can be removed only while no published database exists, so no accepted command is discarded. Unknown/nonempty unmarked directories are refused.

An existing profile is opened under the lock for explicit SQLite journal recovery and `quick_check`, then verified against profile version 1 and exactly the audited base migration sequence 1–25. It never calls `DatabaseConfig.initialize()` on that published database. Foreign, older/newer or invalid profiles fail closed. No profile is copied, upgraded, reset or deleted automatically. There is consequently no migration that needs to modify a valuable database before making a backup. Future compatibility changes must separately design a locked backup/restore and migration path. For a manual experimental backup, close every owner and copy the entire profile and operation directories together; preserve the UUID and receipt ledger.

Every subsequent state/receipt query opens an explicit SQLite `mode=ro` connection with `PRAGMA query_only=ON`. No reconciliation, ledger last-seen update or incidental initializer is permitted. Integration tests compare the entire database byte-for-byte before/after queries and prove writes fail on that connection. Startup hot-journal rollback can write; it is documented separately from the no-write query guarantee.

## Atomic command and client recovery

Transport UUIDs correlate individual requests. A separate command UUID identifies one deliberate deck-creation intent. Main creates and fsyncs a bounded journal record containing the exact input, profile UUID and command UUID before sending any create request. Atomic rename publishes it; directory fsync adds Unix crash durability. The same intent remains until Java has returned a terminal receipt and the user acknowledges it.

Java checks the selected profile UUID and input bounds. One SQLite transaction inserts the deck and an immutable receipt, or persists a terminal `NAME_EXISTS` rejection. Reusing the same command UUID and exact raw input returns the same receipt; changing the input under that UUID returns `COMMAND_ID_CONFLICT`. Normalized deck values do not replace the original input identity. A ledger insert failure rolls back the deck. An interrupted pre-commit transaction rolls both back. A lost post-commit response leaves the receipt available.

On renderer/app restart, `pendingDeck()` reads the main journal and issues read-only `commands.status`. If no receipt exists, completion remains unknown. Only the explicit **Check and finish saved creation** action may replay the original command and payload; it checks status first. It never mints a replacement command UUID. The saved profile UUID prevents applying a stale operation to a freshly recreated database. Confirmed success/rejection is acknowledged through another Java receipt lookup before the journal is cleared. A distinct intentional request with an existing deck name receives a rejection, never a success attributed to somebody else's deck.

Cancellation and deadlines stop the transport and wait for the process to exit. They do not promise a write rollback. The UI suppresses stale responses by generation, shows unknown completion when appropriate and reconciles the durable receipt. One request per sidecar and one current mutation intent bound concurrency without queueing unrestricted work.

## Security and scope

The [exact API and ownership map](gate2/api.md) describe the three Java methods and six preload functions. Strict versioned JSON is confined to private inherited stdio, with byte limits, strict UTF-8, duplicate-key rejection, exact fields, IDs, timeouts and result-kind/profile correlation. Invalid frames close the sidecar. No reflection, arbitrary SQL, general file path, shell command, content URL or localhost HTTP service is exposed. Java is the final authority; TypeScript validates transport shape and lifecycle only.

Existing sandbox/contextIsolation/CSP/local-content/owner-frame checks remain enabled. Environment Java injection variables are removed; only the bundled Java 21 runtime is selected. Logs and error codes never carry SQL exceptions, learner content or filesystem paths into the renderer.

Evidence, mastery, rewards, adaptive quests, assessment exposure, problem authorization, drafts, frozen submissions, grading, compilation, runner cancellation and P2P remain unsupported endpoints. Their existing JavaFX safeguards and `IsolatedDatabaseExtension` remain intact. No Gate3/Gate4 work or JavaFX removal is included.

## Review evidence and remaining limits

See the [review walkthrough](gate2/README.md) and [verification](gate2/verification.md). The Linux unsigned artifact and fault tests are evidence for this slice, not Windows/macOS packaging or power-loss certification. SQLite commits use `synchronous=FULL`; actual power-loss guarantees depend on the filesystem/device. Directory fsync is additionally performed on Unix; Windows publication/shutdown/native validation remains a later gate.

Nonblocking Gate1 follow-ups remain tracked: graph branch membership metadata, spatial arrow traversal, and reduced-motion scroll behavior. They are not implemented in this IPC-focused change.
