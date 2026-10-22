# Gate 2 learner read integration: pre-implementation audit

Baseline inspected: `78898a4a605768f582ef3a3f8835afbe1fb2f286` (latest main, 2026-10-22). Open PR #255 is unrelated JavaFX UI work. This work uses its own branch/worktree.

## Side effects and ownership

| Existing boundary | Finding | Ownership in this slice |
| --- | --- | --- |
| `DatabaseConfig` | Default is **working-directory-relative** `codefit.db`, not a global profile folder. `initialize()` creates/alters tables, inserts defaults, backfills rows, runs migrations and seeds content. Mutable process-global URL. | Never initialize or redirect it for learner access. Native file selection defaults to the launch directory; no filesystem scan. |
| `SchemaMigrator` | 25 migrations; content and structural migrations have transactional writes. `schema_migrations` is version history, not a cryptographically authenticated application identity. | Accept a structurally validated exact supported schema; reject older/newer/incomplete histories without migration. |
| `ProfileLock`, `CodeFitApplication` | Current JavaFX locks before initialization and holds ownership until process exit. It also starts evidence backfill and UI/background lifecycle. Older binaries/external tools ignore this advisory lock. Existing lock resolves symlinks. | New read path validates paths itself and participates in ownership for capture only. No JavaFX lifecycle. SQLite read transactions protect consistency against nonparticipating writers; no learner write mode exists. |
| `PlayerSystemService.overview()` | Calls adaptive recommendation and effort reward reconciliation, which can publish decisions/estimates and award XP. | Not exposed. |
| `ProgressionService.snapshot()` | `pendingEffortCount()` calls `pendingEffortBatch()`, which persists terminal/quarantine markers. Multiple database connections also preclude a coherent multi-field view. | Direct bounded projection of recorded XP/level only; no rewards, ranks or entitlement claims. |
| `MasteryEstimateService` | `estimate`, `pilot`, `publish`, `reset` can persist revisions/epochs. Capture/prepare helpers require precise evidence/version policy. | Mastery explicitly unavailable in this slice; never inferred from engagement. |
| `AdaptiveQuestService` | Recommendations can publish decisions/estimates and reconcile milestones. Start/respond changes choices/exposure. | Recommendations and missions explicitly unavailable for connected data. Sample missions stay labelled samples. |
| `ReviewService` | Constructor reads preferences; queues combine due cards with introduction/workload budgets. Submission changes schedules, history, progression and daily quests. | Aggregate due scheduled-card count only, reusing repository state/date predicate; not advertised as a complete session queue. |
| Repositories | `UserProgressRepository.getProgress()` fabricates defaults for a missing row. Social profile reads include blobs/bio. Catalogs and evidence contain protected material. | Explicit null availability for missing progress; bounded name-only social projection; no card text, answers, challenge assets, evaluations, draft or peer secrets in DTOs. |
| Electron supervision / v2 IPC | Strict finite commands, Java process watchdog, renderer/frame authorization, sandbox and journal already exist. Session rejects concurrent status/read as BUSY. Journal retains immutable experimental deck intent until acknowledged. | Preserve experimental store/journal. Separate read-only learner process and v3 DTOs; bounded serialized requests, generation/session identity, timeout/cancel/disconnect, stale-reply suppression. |

## Smallest production slice

Explicit native file selection and confirmation connect a learner **snapshot**, never a writer. Java opens the selected file with SQLite `mode=ro` (never `immutable` on a live source), takes a read transaction, validates identity/schema and captures with SQLite's online backup API. It validates integrity and supported shape before atomically publishing a private, bounded local backup. Projection reads the completed backup only. Source ownership is released after capture; newer external changes therefore require an explicit reconnect/capture and the UI shows snapshot time.

Private backup files contain the full selected database, including sensitive local content; they need restrictive permissions and are never exposed to React. No automatic source replacement/restore occurs. Interrupted captures are never presented as valid. Recovery uses a validated completed backup as a separately selected source and never overwrites the original. Native Windows/macOS durability and filesystem semantics need platform review.

## Risk boundary

An advisory lock cannot prove an older JavaFX process or external SQLite writer is absent. A read transaction plus SQLite online backup can provide a consistent snapshot without a second writer. `mode=ro` protects SQLite database content but WAL readers can touch shared-memory sidecars; the UI/docs must not promise zero filesystem changes. Hot-journal/recovery requirements, unsupported file forms, incompatible schemas, corruption, timeouts and path identity changes fail closed. No unsafe recovery or migration fallback is permitted.

Path validation rejects symlinks in every existing component and hard links where supported, checks regular-file identity before/after capture, and refuses unsupported filesystems/identities. A malicious actor with write access to the selected directory remains outside a full OS isolation guarantee; this slice adds no source write capability even in that case. Untrusted SQLite structures are queried with trusted schema disabled and only fixed, allowlisted SQL.

API owners: React owns presentation and current-generation state; Electron owns native selection/explicit opt-in, process lifetime and frame authorization; Java owns schema/identity validation, backup, aggregate read DTOs and errors; SQLite owns transactional snapshot consistency. Production writes, mastery replay, adaptive publication, challenge authorization/evaluation, drafts, runner and P2P remain separate gates.
