# Gate 2 continuation: read-only learner snapshots

This focused draft builds on merged PR #264 for [#261](https://github.com/MrH3lmy/CodeFit/issues/261) under [#256](https://github.com/MrH3lmy/CodeFit/issues/256). It connects authentic selected learner records to the approved Command Center. It does not complete the migration or authorize learner writes.

- [Pre-implementation audit](audit.md)
- [Architecture, ownership, backup and compatibility decision](../adr-0003-learner-snapshots.md)
- [Exact typed API](api.md)
- [Verification and limitations](verification.md)

## Review the actual application

Build the packaged app using the [desktop instructions](../../../desktop/README.md). Open **Connect profile** or the profile avatar, then **Choose learner profile**. JavaFX's default database is `codefit.db` in its launch directory; the app does not scan your disk or assume a global location.

The native file picker is followed by a separate confirmation identifying the selected file and explaining the private full backup and coordination metadata. Cancel is the default. Close JavaFX before capture. In development and review, select only a synthetic/disposable profile copy. No real learner profile was used by the tests or screenshots.

Once connected, close the profile dialog to inspect recorded engagement and review summaries in the Command Center. The capture timestamp is visible; this is a historical copy, not a live connection. Mastery, adaptive recommendations and active missions remain explicitly unavailable. Ascension Tree, Mission Console and concept actions remain labelled demonstrations. The Java Library retains its separate experimental profile and recoverable deck creation.

Reloading/restarting disconnects the view and requires fresh consent. Selecting a new profile first stops the old reader. Cancel or timeout never restores an old profile as if it were the new selection.

## Backups and recovery

The existing independent application-data directory is normally `~/.config/CodeFit-Gate0` on Linux. Its `learner-snapshots-v1` child holds private capture files. This directory is 0700 and completed UUID `.sqlite` files are 0600. They contain the full profile, potentially including assessment answers, drafts and P2P keys. Keep them private. No renderer method can read/download them or expose their path.

Capture uses SQLite online backup under a pinned read transaction, then validation, fsync and atomic publication. It never raw-copies an active source, migrates it or replaces it. Source learning records stay read-only, although lock/shared-memory sidecars may be created/updated as disclosed before consent.

The hard bound is eight completed/interrupted generations and 64 MiB per database/sidecar. There is no automatic rolling deletion. If full, close all app owners and archive completed backups to another private location before reconnecting. An interrupted `.partial` is never presented as a valid backup; retain it for review or deliberately remove it only after closing the app. Never delete an ordinary learner `codefit.db`, journal or WAL as a workaround.

To verify recovery without touching the original, close app owners and copy a completed backup to a **new disposable path outside the private store**. Explicitly select that copy through the same consent flow. Existing backups and the original profile are not overwritten. Automated tests cover this restoration and failed-capture preservation.

## Failure walkthrough

| Event | Result | Recovery |
| --- | --- | --- |
| Cancel file picker or confirmation | No learner reader opens | Select again when ready |
| Current JavaFX holds ownership | `PROFILE_BUSY` | Close JavaFX and retry |
| Older/external SQLite writer | SQLite read transaction gives a coherent capture, or fails busy | Prefer closing other tools; this never authorizes a source writer |
| Old/future/incomplete schema or foreign DB | Explicit refusal, no migration | Keep original; use a disposable compatible copy for review |
| Hot journal or corruption | Refused; no source recovery | Preserve files and use separately reviewed recovery tools |
| Java/main crash or timeout during capture | No partial generation is shown; prior complete backups remain | Restart, review backup storage, explicitly choose again |
| Renderer reload or late confirmation | Selection generation is invalidated | New consent is required |
| Source changes after capture | Displayed data stays at its labelled capture time | Explicitly select/capture again |
| Store/source exceeds bounds | Capture blocked | Archive private backups or use a supported bounded disposable profile |
| Unsupported platform/filesystem | Fails closed | Continue using JavaFX pending native validation |

## Screenshots

Actual packaged-app screenshots use synthetic profile **Ada Learner** with deliberately populated records. They demonstrate real Java reads, not fixture values embedded in React.

| Theme | Desktop | Narrow |
| --- | --- | --- |
| Dark | [1280px](screenshots/dark-1280.png) | [760px](screenshots/dark-760.png) |
| Light | [1280px](screenshots/light-1280.png) | [760px](screenshots/light-760.png) |

## Exact next gates for #261

1. Review this read-only selection, backup and projection boundary; validate native Windows/macOS and additional filesystem behavior before enabling those targets.
2. Audit further pure read projections independently: current evidence/mastery eligibility, persisted adaptive decisions and mission state need invalidation/freshness rules and protected-content redaction. Do not substitute `overview()` or recommendation publication.
3. Design a separate learner-write gate with explicit authorization, ownership compatible with legacy binaries, safe schema migration plus recoverable backups, and durable command receipts. This PR grants no learner-write capability.
4. Migrate review/problem/assessment/exposure/draft/frozen-submission/runner/P2P workflows one contract at a time, preserving their Java policies and recovery/consent boundaries.
5. Reassess every #261 acceptance criterion before closing it. Do not start Gate 3 or remove JavaFX on the strength of this slice.
