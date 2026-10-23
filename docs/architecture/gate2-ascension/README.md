# Gate 2: evidence-backed Ascension Tree

Continues [#261](https://github.com/MrH3lmy/CodeFit/issues/261) under [#256](https://github.com/MrH3lmy/CodeFit/issues/256), from merged PR #265 at `febc92aad4a486b72dfb836e9a1ad7ca0487b9eb`. The approved Ascent design now reads actual published Java skill definitions, exact prerequisites and saved ordinal interpretations through the consented learner snapshot.

- [Pre-implementation side-effect audit](audit.md)
- [API contract and ownership](api.md)
- [Verification, screenshots and review](verification.md)
- [Existing capture architecture](../adr-0003-learner-snapshots.md)

## What the user sees

Choose a compatible disposable learner profile through **Connect profile**, confirm the native consent, and open **Ascension Tree**. The frozen capture timestamp remains visible. The diagram renders the exact `java-debugging-concurrency` v1 graph chosen by Java's existing pilot constant; the renderer does not import a replacement taxonomy. Nodes expose stable references, authored descriptions, ordered observable behaviors and actual prerequisite edges. Domain filtering uses explicit definition membership. Every node stays inspectable; prerequisites describe learning order and do not grant or deny unlocks.

The Evidence tab distinguishes missing, unavailable and historical estimates. A historical revision displays its original ordinal band, support, freshness, contradictions, behavior coverage, family count, finite reason codes and aggregate provenance/assistance/exposure counts. **All are labeled at the original estimate time.** Java replays the existing saved `ordinal-v1` interpretation without publishing anything. There is no percentage, XP conversion or TypeScript estimation policy.

Current capability is **not evaluated** by this slice. A saved revision can be faithfully replayed even after its source is withdrawn; it must never be presented as active support. Factual evidence withdrawal/revision/missing-record, mapping-change and epoch-change warnings are attached where available. Their absence does not validate source eligibility. Neither a historical `CURRENT` freshness value nor a historical `INDEPENDENT` band is displayed without its historical scope. The original as-of and snapshot capture dates are both shown. No green completion checks, reward indicators, mission actions or earned unlocks are inferred from these records.

A missing exact graph, invalid catalog or over-limit graph is visibly unavailable. A newer graph is disclosed without substituting its prerequisites. An invalid/latest version-mismatched revision never falls back to an older positive result. New learners see authored capabilities with no saved estimate; this is not zero ability.

Without a connected profile, the existing illustrative tree remains explicitly demonstration content. Mission Console remains a demonstration even while the learner graph is connected. Refresh reads the same frozen copy; only **Capture again** with fresh native consent can obtain later source changes.

## Interaction and accessibility

The existing layered panels, node shapes, paths, inspector, themes and navigation are reused. Zoom and scroll/trackpad pan are retained. Arrow keys move focus to a spatial neighbor in the requested direction; Home/End reach endpoints in deterministic node order. Enter selects the focused node. Prerequisite buttons navigate exact authored parents. Narrow layouts expose an inspect action that focuses the inspector heading. Programmatic scrolling honors reduced motion. Domain filtering dims unrelated nodes while retaining prerequisite context.

## Read-only and privacy boundary

PR #265's native consent, ownership checks, pinned online backup, file identity checks, private permissions, size/retention caps, source read-only connection, no migration, cancellation, process supervision and no automatic reconnection remain intact. The new zero-argument preload capability accepts no path, graph ID, SQL or arbitrary method. Main and Java authorize the existing profile/session; React also rejects mismatched profile/session/capture results. Profile switches immediately clear the old view. Overview and skill reads share one serialized bounded process; duplicate calls of each kind coalesce.

No source learning record, estimate revision, reward, recommendation, exposure, draft, attempt, evaluation, peer aggregate or signed payload is written by the new read. No domain service initializes the source. The graph read opens only the already captured private database in a query-only read transaction. Responses use an explicit field allowlist, not reflection serialization. No artifact, answer, hidden check specification, evidence payload, source path or peer secret crosses IPC.

### Full unencrypted snapshots

The existing full backups include more data than the UI displays, potentially answers, drafts, historical evidence and peer private keys. Mode 0700 directories and 0600 files restrict other local accounts; they do not encrypt data or defend against another process using the same account, administrator access, an unlocked device or raw unencrypted storage. A derived read-only projection does not remove this backup risk. Disk encryption and private unsynchronized storage are recommended for review copies. No claim of secure deletion is made.

Storage remains bounded at eight completed/interrupted capture generations, with 64 MiB per database/sidecar. The app refuses further capture rather than deleting history. The profile dialog explains the practical cleanup procedure: disconnect, close all CodeFit owners, archive chosen complete UUID `.sqlite` files to private encrypted storage, verify the archive, then deliberately remove chosen local copies. Interrupted `.partial` files are not verified backups. The [capture guide](../gate2-profile/README.md#backups-and-recovery) identifies the Linux private store and disposable-copy recovery procedure. Never remove the original learner database, journal or WAL as cleanup. SSD wear leveling, OS backups and synchronization can retain deleted bytes; secure archival and erasure depend on external storage controls. There is no renderer filesystem/delete endpoint and no silent retention policy.

### Native platform gate

Learner-profile access remains disabled outside the validated local Linux filesystem allowlist. Windows/macOS need actual native validation before enablement: package/runtime launch and signing, native consent/dialog lifecycle, file identity and symlink/reparse handling, hard links, exclusive/cooperative locks across JavaFX/Electron, WAL/rollback consistency, process termination, ACL/private backup permissions, atomic move and directory durability semantics, interrupted startup, bounded storage and renderer reload/profile switching. Test actual NTFS/APFS and explicitly reject unvalidated network/removable/synchronized cases. An OS-name change or simulated filesystem test is not native verification.

## Next reviewable work

1. Review the historical-only semantics and native platform gate; keep #261 open.
2. For current skill support, design a bounded metadata-only current eligibility read using the existing evidence/evaluator owners, mapping transitions, epochs, source availability and freshness. It must avoid publication and private artifact loading; absence of lifecycle warnings is insufficient.
3. Audit persisted adaptive decisions separately: a saved recommendation may be invalid after corrections, goal changes or exposure. Explain its original time and validate the exact input references before making an actionable recommendation. Do not call `recommend()` or `overview()` as a read.
4. Read mission status through exact persisted catalog/choice/submission metadata. Protected prompts still require the existing exposure-before-content boundary; no preview should authorize execution or create a presentation.
5. Keep all real-profile writes, migrations, challenges, evaluation, drafts and Gate 3 behind their separately reviewed contracts. JavaFX remains available throughout.
