# ADR 0003: explicitly selected, read-only learner snapshots

Status: implemented for draft review under [#261](https://github.com/MrH3lmy/CodeFit/issues/261) / [#256](https://github.com/MrH3lmy/CodeFit/issues/256). Baseline `78898a4a605768f582ef3a3f8835afbe1fb2f286` includes merged PR #264. JavaFX remains the learning application. This does not complete #261 or authorize Gate 3.

## Decision and audit

The [pre-implementation side-effect audit](gate2-profile/audit.md) rules out dashboard calls to `PlayerSystemService.overview()`, `ProgressionService.snapshot()`, mastery derivation, adaptive recommendation, and initialization. They can reconcile rewards or publish state. A new Java read projection selects recorded facts on one caller-owned SQLite transaction. Java remains the authority; TypeScript neither calculates learning policy nor opens SQLite.

The normal database is working-directory-relative `codefit.db`. There is no reliable global learner directory to scan. Electron offers a native file picker, initially suggesting the launch directory, followed by a separate native confirmation identifying the selected file. Cancel is the default. No database read begins before confirmation. Frame authorization and selection generation are checked again after each asynchronous step.

## Capture and ownership

The selected source opens using SQLite `mode=ro`, `query_only=ON`, extension loading disabled and `trusted_schema=OFF`. It never uses `immutable=1` on the source, `DatabaseConfig.initialize()`, a migration, recovery writer, or a learner mutation service. Current JavaFX's canonical profile lock is acquired for capture, before the SQLite connection. The source lock is released after capture; JavaFX may subsequently update the original while the displayed copy remains frozen.

Older JavaFX binaries and external tools ignore that cooperative lock. SQLite's own pinned read transaction and online backup supply snapshot consistency when a nonparticipating SQLite writer is present. This is **not exclusive writer ownership** and does not authorize a future learner writer. A hot/nonempty rollback journal is refused; this path cannot recover the source. WAL readers can create/update shared-memory coordination metadata, and the lock sidecar may be created. Consent explicitly discloses those filesystem changes. Learning records are never modified.

Before capture, Java rejects symbolic path components, nonregular files, hard links, unavailable file identities, oversized source/sidecar files and unsupported filesystems. It compares source file identity again after opening and after backup. Only local Linux ext4, xfs, btrfs, overlay and tmpfs are currently allowed. Windows/macOS, network filesystems and FUSE need native validation. File checks do not establish protection against a malicious same-user actor who can replace directory entries between checks.

The source read transaction validates the exact migration sequence 1–25 and required real tables/columns, excludes known experimental profiles, and pins the SQLite snapshot. The version ledger is compatibility metadata, not cryptographic authentication. Older/future/incomplete schemas fail closed without migration. Fixed SQL and bounded metadata projections avoid exposing arbitrary schema or private content.

## Private backups and failure recovery

SQLite online backup copies the pinned transaction into a newly allocated private `.partial` file. Raw file copying is not used for capture. The destination is checked for compatible schema, `integrity_check(1)` success and a valid projection, synced and atomically renamed to a new UUID `.sqlite` generation. Existing generations are never replaced. Directory sync completes publication on the supported platform.

The main-owned `learner-snapshots-v1` directory has POSIX mode 0700 and backup files 0600. Backups contain the **full database**, potentially including assessment answers, drafts and P2P keys. They stay local and are never exposed through renderer paths, downloads or data DTOs. Only the explicit aggregate projection crosses IPC. The private store has its own process lock through session exit.

Each source main file, sidecar and completed backup is bounded at 64 MiB; the pinned page count/size is checked before native copying. At most eight complete or interrupted generations are allowed. This is a hard storage limit, not automatic rolling deletion. Once full, the user must close the app and archive completed learner backups before capturing more. The application never recursively deletes selected learner directories. Ordinary failed attempts remove only the partial file allocated by that attempt; hard crashes can leave an inert partial file that still counts toward the bound. Earlier completed backups survive.

Native backup ignores progress-observer exceptions and `sqlite3_interrupt()` in the shipped JDBC version. Electron enforces a 20-second startup/request deadline, closes stdin and escalates to SIGTERM after 100 ms / SIGKILL after 1 second, awaiting exit before replacement. Java independently bounds capture at 25 seconds and halts within its 200 ms parent-loss watchdog. SQLite SQL reads have a progress deadline. No callback-only cancellation claim is made.

There is no automatic source restoration. To inspect a completed backup, close all owners, copy it to a **new disposable path** outside the private store, and explicitly select that copy. Restore tests verify this produces the same recorded facts without replacing the original. Device-level power-loss durability and unsupported native filesystem behavior are not certified.

## IPC, presentation and scope

A separate learner sidecar uses [strict protocol v3](gate2-profile/api.md), bound to source file identity and a random session UUID. The experimental deck sidecar and durable v2 command journal remain separate. No learner write method exists. Coalesced reads share one bounded in-flight request; selection, cancellation, reload and replacement invalidate older generations before they can publish a result.

The approved Command Center composition and artwork remain. Connected sections show recorded display name where available, recorded XP/level, review-history count and due scheduled-card count for the capture's UTC day. Missing/invalid progress remains unavailable rather than defaulting to level 1/zero XP. XP has no mastery meaning. Mastery, adaptive recommendations and mission status are explicitly unavailable. Other screens and concept actions retain demonstration labels and their sample-workspace safeguards.

The view always identifies a historical snapshot and capture time. It does not silently reconnect after reload/restart. A new capture requires explicit selection and confirmation. Java's DTOs omit paths, card content, answer keys, evidence artifacts, evaluation internals and peer data. No reward, estimate, quest, exposure, draft, submission, runner or P2P mutation endpoint is introduced.

See [verification and remaining gates](gate2-profile/verification.md) and the [review walkthrough](gate2-profile/README.md).
