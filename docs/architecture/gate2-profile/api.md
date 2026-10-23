# Learner read API and ownership

This records the PR #265 contract. The additive [Ascension API](../gate2-ascension/api.md) adds `readLearnerSkills` / `learner.skills.read` with a separate 256 KiB skills response bound; the original overview and startup bounds below remain unchanged.

Executable schemas: [learner-contract.ts](../../../desktop/src/learner-contract.ts) and [LearnerProtocol.java](../../../src/main/java/com/codefit/desktop/LearnerProtocol.java). [ADR 0003](../adr-0003-learner-snapshots.md) defines capture and privacy guarantees.

## Renderer surface

Only three allowlisted methods are added to the frozen preload bridge:

```ts
chooseLearnerProfile(): Promise<LearnerResult>; // Native selection + confirmation, then capture/read
readLearnerProfile(): Promise<LearnerResult>;   // Read the existing frozen session, never recapture
disconnectLearnerProfile(): Promise<void>;     // Invalidate selection and await sidecar exit
```

None accepts a renderer-supplied path, SQL, backup destination, runtime, profile ID or command. Main validates the exact zero argument count, owning WebContents/current main frame and bundled entry URL. It revalidates after dialogs, teardown and execution. An invalidated confirmation cannot open a source. Cancellation of a new selection leaves the old reader disconnected.

The original six experimental-library methods and v2 durable intent/receipt semantics remain intact. Experimental deck creation cannot target a learner snapshot.

## Private stdio v3

Every frame is strict UTF-8 JSON plus LF. Requests have a 1,024-byte limit; ready/results have an 8,192-byte limit. Duplicate keys, BOMs, malformed encodings, trailing JSON, partial EOF, unknown/extra fields and mismatched types/versions are rejected. Stderr is drained with a 4,096-byte cap and never sent to React.

```ts
type UUID = string; // lowercase RFC4122 v4, exact validator in the schemas
type SourceId = string; // lowercase SHA-256 of selected path + stable file identity; not an account ID
type Ready = { v: 3; type: "ready"; profileId: SourceId; sessionId: UUID };
type StartupError = { v: 3; type: "startup-error"; code: LearnerError };
type Request = {
  v: 3; id: UUID; method: "learner.overview.read";
  profileId: SourceId; sessionId: UUID;
};
type Response =
  | { v: 3; id: UUID; ok: true; result: LearnerProjection }
  | { v: 3; id: UUID; ok: false; code: LearnerError };
```

Java validates both identities for every request. Main validates request correlation and projection identities against the ready frame. Selection/session UUIDs fence a replaced profile even when a physical path is reused. Concurrent reads coalesce onto one request. New selection awaits old process termination. Runtime discovery is lazy; a missing bundle produces a typed error rather than preventing the shell from opening.

## Projection v1

```ts
type LearnerProjection = {
  v: 1; kind: "learner-overview"; source: "learner-snapshot";
  profileId: SourceId; sessionId: UUID;
  capturedAt: UTCInstant; schemaVersion: 25; readOnly: true;
  identity:
    | { status: "available"; displayName: string } // 1..120 UTF-16 units, no controls
    | { status: "unavailable"; displayName: null };
  progression:
    | { status: "available"; xp: NonnegativeSafeInteger; level: PositiveSafeInteger }
    | { status: "unavailable"; xp: null; level: null };
  activity: { reviewCount: NonnegativeSafeInteger; dueScheduledCards: NonnegativeSafeInteger; reviewDay: UTCDate };
  mastery: { status: "unavailable" };
  recommendations: { status: "unavailable" };
  missions: { status: "unavailable" };
};
```

`capturedAt` and `source` define freshness: the result is a frozen historical capture, never a current live query. The UI prints the timestamp/time zone and asks for a new explicit capture for newer information. The source identity is local file identity, not verified person identity.

XP/level are persisted values, not recalculated or reconciled. Missing, negative, noninteger or unsafe values are unavailable. Review count is `review_history` row count, independent of the cached legacy `total_reviews` counter. Due count reuses `FlashcardRepository.findDueCards`' scheduled-state/date predicate at the capture's UTC date; it excludes new-card budgets and session caps and is labelled accordingly. No full queue or mastery is inferred.

The error vocabulary extends the existing redacted transport codes with `INCOMPATIBLE_SCHEMA`, `SOURCE_CHANGED`, `SNAPSHOT_LIMIT`, `SNAPSHOT_FAILED`, and `UNSUPPORTED_PLATFORM`. Failure codes carry no SQL, source path, source content or stack trace. Failure to read or validate is not a learner performance judgment.

## Owners and mutations

| Boundary | Owns | Permitted effects |
| --- | --- | --- |
| React | Snapshot presentation, local input/busy/error state, stale-response generation | No persistence or policy computation |
| Preload | Three typed invocations | No Node/fs/process access exposed |
| Electron main | Native selection/consent, fixed private store, frame/session authorization and supervision | Private process/selection lifecycle only |
| Learner Java sidecar | Source validation, SQLite read transaction, online backup, projection and errors | Private backup publication; cooperative lock and SQLite coordination sidecars |
| SQLite | Transactional consistent source read and backup | No source learning-record writes |
| Existing JavaFX/domain services | Actual learning, rewards, mastery, adaptation and execution | Remain behind existing JavaFX contracts |

No general filesystem, SQL, reflection/RPC, HTTP, event subscription or mutation endpoint is added.
