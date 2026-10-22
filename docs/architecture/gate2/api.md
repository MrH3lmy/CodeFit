# Gate2 API v2 and ownership map

The executable exact renderer/wire schemas are [desktop/src/contract.ts](../../../desktop/src/contract.ts). The final Java validator/serializer is [ApplicationProtocol.java](../../../src/main/java/com/codefit/desktop/ApplicationProtocol.java). Extra fields, duplicate object keys, invalid scalar types and mismatched versions are rejected. No serializer reflects domain objects wholesale.

## Framing and values

Private stdio frames are strict UTF-8 JSON followed by one LF. BOMs, UTF-16, invalid UTF-8, trailing JSON and partial EOF are invalid. Requests are at most **16,384 bytes excluding LF**; responses/ready frames at most **65,536 bytes excluding LF**. Main retains at most **16,384 bytes of stderr count**, not log content. Main startup and each transport request have a **15-second deadline**. There is one in-flight transport request; concurrent requests return `BUSY`. Main closes stdin, sends SIGTERM after 500 ms and SIGKILL after 2 seconds if necessary, and waits for exit before replacement. Java checks parent liveness every 200 ms.

`UUID` means lowercase RFC4122 version-4 text matching `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`. IDs/cursors are integers representable exactly in JavaScript, `0..9007199254740991`; deck IDs and nonnull next cursors are positive.

Input name and description limits are **256 and 2,048 UTF-16 code units** respectively, aligned between Java `String.length()` and JavaScript `string.length`. Text excludes U+0000–001F, U+007F–009F and unpaired surrogates. Name must contain a character outside Java's `Character.isWhitespace` set. Description is required in the wire/input DTO but may be empty. Java's existing service trims values and supplies the existing default description. That normalization is domain behavior, not command-identity normalization.

## Exact Java request union

```ts
// Every object is strict: fields shown are the complete field set.
type ReadState = {
  v: 2; id: UUID; method: "state.read"; afterId: SafeInteger;
};
type CreateDeck = {
  v: 2; id: UUID; method: "decks.create";
  profileId: UUID; commandId: UUID; name: Text256; description: Text2048;
};
type CommandStatus = {
  v: 2; id: UUID; method: "commands.status";
  profileId: UUID; commandId: UUID;
};
```

`id` is newly generated per transport attempt. `commandId` and the exact original `name`/`description` survive retries. `profileId` must match the owned profile; main also compares it with Java's ready frame before dispatch. Renderer callers never supply these command/profile identities to creation or recovery.

## Exact Java output union

```ts
type Startup =
  | { v: 2; type: "ready"; profileId: UUID }
  | { v: 2; type: "startup-error"; code: "PROFILE_BUSY" | "PROFILE_INVALID" };
type Deck = { id: PositiveSafeInteger; name: Text256; description: Text2048 };
type State = {
  kind: "state"; source: "experimental-profile"; profileId: UUID; readOnly: true;
  progression: { status: "unsupported" };
  decks: Deck[]; // 0..8 descriptors, ordered by increasing id
  nextAfterId: PositiveSafeInteger | null;
};
type Receipt =
  | { kind: "receipt"; commandId: UUID; status: "created"; deck: Deck }
  | { kind: "receipt"; commandId: UUID; status: "rejected"; code: "NAME_EXISTS" };
type Status = { kind: "command-status"; commandId: UUID; receipt: Receipt | null };
type Response =
  | { v: 2; id: UUID; ok: true; result: State | Receipt | Status }
  | { v: 2; id: UUID; ok: false; code: ErrorCode };
```

Main validates result kind for the outstanding method, transport ID, state profile UUID, status/receipt command UUID and nested receipt identity. Unexpected, duplicate, late or uncorrelated frames terminate that transport. Each returned page is a fresh ordered query; it is not a historical multi-page snapshot. A concurrently created deck has a larger ID and can appear on a later page.

Malformed requests, wrong versions, unknown methods, missing/extra fields and invalid input syntax close Java without interpreting a command. For valid envelopes Java may return `READ_FAILED`, `COMMAND_FAILED`, `INVALID_INPUT`, `PROFILE_MISMATCH` or `COMMAND_ID_CONFLICT`. Startup errors are the two explicit codes above. The shared transport error vocabulary also includes main-generated `START_FAILED`, `UNAVAILABLE`, `PROTOCOL_ERROR`, `TIMEOUT`, `CANCELLED`, `BUSY`, `JOURNAL_INVALID`, and `RECOVERY_REQUIRED`. No free-form exception message crosses the bridge. `COMMAND_FAILED` or transport failure is not evidence of rollback; reconciliation is mandatory.

| Java method | Authorization | Reads | Writes / transaction | Retry semantics |
|---|---|---|---|---|
| `state.read` | Finite protocol allowlist, owned selected profile | Bounded deck descriptors only | None; `mode=ro` + `query_only` | New request UUID; no side effects |
| `commands.status` | Exact profile UUID and command UUID | One immutable command receipt | None; `mode=ro` + `query_only` | Null means no committed receipt was found; never implicit success |
| `decks.create` | Exact profile UUID, command UUID, bounded valid text | Existing command/payload | One deck plus receipt, or terminal rejection receipt; one SQLite transaction | Same command/payload returns receipt; changed payload conflicts |

## Exact preload surface

The bridge object is frozen and contains only these six functions. Electron authorizes exact argument count, the live owning WebContents, its current main frame, and the exact bundled entry URL. Unknown renderer frames never reach the engine or journal.

```ts
readState(afterId: number): Promise<Result>;
createDeck(input: { name: string; description: string }): Promise<OperationResult>;
pendingDeck(): Promise<OperationResult>;
recoverDeck(): Promise<OperationResult>;
acknowledgeDeck(commandId: string): Promise<AckResult>;
cancelOperation(): Promise<void>;

type Result = { ok: true; result: State | Receipt | Status } | Failure;
type Failure = { ok: false; code: ErrorCode };
type Intent = {
  v: 1; profileId: UUID; commandId: UUID;
  input: { name: Text256; description: Text2048 };
};
type Operation = Intent & { receipt: Receipt | null; error: ErrorCode | null };
type OperationResult = { ok: true; operation: Operation | null } | Failure;
type AckResult = { ok: true } | Failure;
```

`createDeck` accepts only the validated input. Main first obtains the selected profile identity, persists one intent, then dispatches it. `pendingDeck` performs status-only reconciliation. `recoverDeck` checks status and, only if absent without an error, replays the exact saved intent. `acknowledgeDeck` verifies the current journal ID and a terminal Java receipt before clearing it. It cannot abandon an unresolved intent. `cancelOperation` stops the owned connection, retaining the intent and ledger; it is not a database rollback API.

The journal has its own version 1 (independent of wire v2), strict schema above and the 16,384-byte bound. It rejects symlinks, hard links, malformed UTF-8/JSON, duplicate keys and extra fields. Temporary writes use exclusive creation, file fsync, atomic rename and Unix directory fsync. Crash leftovers before rename are inert and never dispatched. No recovery endpoint lists historic commands or accepts arbitrary paths/payloads.

## Data and lifecycle ownership

| Component | Owns | Must not own |
|---|---|---|
| React Java Library | Input, paging cursor, typed busy/confirmed/rejected/unknown UI states, stale-response generation | SQLite, reward/mastery calculations, command identity/durable storage |
| Approved React preview screens | Clearly labeled Gate1 fixtures and existing local preview interactions | Claims of live progression or feature parity |
| Sandbox preload | Six typed IPC invocations | Node/fs/process APIs exposed to renderer |
| Electron main / `DesktopApplication` | Owner-frame checks, fixed experimental paths, durable current intent, profile binding, explicit recovery lifecycle | Deck normalization/business rules or SQLite connections |
| `EngineOwner` / `JavaSession` | One bundled Java process, strict protocol, deadlines, cleanup and replacement sequencing | Domain policy or invented success |
| `ExperimentalProfile` / `ProfileLock` | Canonical cross-process ownership, marked bootstrap, compatibility refusal, explicit startup recovery, explicit JDBC connections | Learner import/upgrade or automated valuable-data deletion |
| `DesktopApplicationService` | Allowlisted read/create/status use cases and ledger transaction | Adaptive/reward reconciliation, assessment content, runners or P2P |
| `DeckService` / `DeckRepository` | Existing Java validation/normalization and insert; bounded descriptor query | Client transport retries or UI state |
| SQLite | Atomic durable deck/receipt state | TypeScript-owned parallel writes |
| JavaFX application | Existing full application and domain safeguards; same cooperative owner lock for JVM lifetime | Simultaneous writes while headless owns that file |

No event bus, subscriptions, bulk commands, generic cancellation targets, public HTTP listener or reflection dispatcher is introduced. Protocol expansion requires an audited domain use case and tests before a new method is allowlisted.
