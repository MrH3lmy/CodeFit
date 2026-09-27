# Threat model and data flows: decentralized peer features

* **Scope:** epic #179 as designed in [ADR-0001](adr-0001-decentralized-peer-architecture.md) and
  [protocol v1](protocol-v1.md).
* **State after #180:** no networking exists yet. The threats below drive requirements for
  #181–#189, and each mitigation names where it is enforced.

## 1. Assets

| Asset | Where it lives | Leaves the device? |
|---|---|---|
| Identity private key, last writer epoch | local, encrypted at rest (#181) | only inside the user's own encrypted backup file |
| Transport private key | local (#181/#182) | never |
| Learning database (cards, answers, code, notes, reflections, imported workbook content, attempts) | `codefit.db` | **never** |
| Contacts, pins, consent state, replay state | local SQLite (#181/#184) | never |
| Approved aggregates: profile card, progress summaries, preparation snapshots | built on demand (#183) | only to recipients whose consent grants the scope |
| Received peers' envelopes | local cache (#184) | never re-shared except as opaque forwarding ciphertext (#188, opt-in) |

## 2. Actors

* **Contact:** a paired peer. Authenticated, but may lie about its own scores, replay old data, or
  misbehave.
* **Group member:** a contact inside a challenge.
* **Stranger:** can reach the listening port, or can obtain frames through a contact.
* **Forwarder:** a consenting peer holding ciphertext (#188).
* **Network attacker:** can observe, modify, drop, replay, and inject traffic.
* **LAN observer:** is on the same broadcast domain.
* **Malicious or compromised library default:** a dependency that phones home or brings a bootstrap
  list with it.
* **Local attacker:** has access to the user's disk while the device is unattended.

## 3. Data flows

```
 learner's device                                                     contact's device
 ┌──────────────────────────────────────┐                   ┌───────────────────────────────────┐
 │ codefit.db (private) ──adapter──►    │                   │                                   │
 │   aggregate builder (#183)           │                   │  EnvelopeCodec.decodeFrame        │
 │     │ approved fields only           │                   │   (bounds → version → signature   │
 │     ▼                                │  TLS 1.3 mutual,  │    → schema → canonical)          │
 │ consent check (#184) ─► sign (Ed25519)├──── pinned ──────►│  EnvelopeAcceptancePolicy         │
 │                        frames (≤64KiB)│  transport keys  │   (audience, time, replay, fork,  │
 │                                      │  (#182)           │    tombstone, revision)           │
 │ local comparisons (#185) ◄─ cache ◄──┤◄──── same ────────┤  consent/scope check (#184)       │
 └──────────────────────────────────────┘                   │  cache → local comparison (#185)  │
                                                            └───────────────────────────────────┘
 First contact: invitation string/QR, sent over a channel the users choose (out of band).
 Optional: LAN multicast announcement (opt-in, #182). Optional: encrypted forwarding via a
 consenting third participant (#188). Nothing else: no service, DNS, time server, or telemetry.
```

Trust boundaries:

* **B1** is between the private database and the aggregate builder. Only the fields in protocol §11
  cross it.
* **B2** is the network. Everything received is hostile until decoded, verified, and accepted.
* **B3** is between the verified cache and the UI. Received text is rendered as plain text, and no
  URL is ever fetched.

## 4. Threats and mitigations

| # | Threat | Mitigation | Where |
|---|---|---|---|
| T1 | **Impersonation** of a contact by a stranger or a network attacker | Mutual TLS pinned to the transport key bound by the pinned identity. Every envelope is signed by the identity key, and the audience is inside the signature | #182, #180 codec |
| T2 | **Tampering** with summaries in transit or in a forwarder's store | Ed25519 over canonical signing bytes. Any modified byte gives `BAD_SIGNATURE` (fixture-tested) | #180 |
| T3 | **Replay** of an old summary or a revoked consent | Duplicate ids are idempotent. Objects order by `(epoch, revision)`. Tombstones are cutoffs, so pre-revocation versions stay rejected while a later re-grant works. Epochs are monotonic. Expiry is enforced | #180 policy, #184 persistence |
| T4 | **Fork or rollback** (two writers, one backup restored repeatedly, revision rollback) | Clock-derived writer epochs per session, bounded by `createdAt`. The `(epoch, sequence)` slot check gives `FORKED`. A newer epoch supersedes rolled-back revisions. The single-writer limitation and the clock/key-loss recovery paths are explicit (protocol §10.1) | #180 policy, #181 backup |
| T5 | **Redirection** of data to an unintended recipient via forwarding | The audience is signed. Non-listed receivers get `UNAUTHORIZED_AUDIENCE`. Forwarded payloads are sealed to the recipient | #180, #188 |
| T6 | **Fake scores** or inflated performance | Out of scope to prevent; *signatures prove authorship only*. Provenance and timestamp basis are shown. Verified correctness excludes self-rating and the legacy fallback. Snapshot overall score, coverage, and status are re-derived from the domains with the engine's arithmetic, so READY cannot hide a failing or partial critical gate, an inflated aggregate, or a score with no measured evidence | #180 contracts, #185/#187 UI |
| T7 | **Sybil identities** or one human with many accounts | Accepted. Contacts are added only by explicit invitation. There are no public rankings; group membership is an explicit list | #182, #186 |
| T8 | **Privacy leak of raw content** (answers, code, notes, workbook text) | No scope or field exists for it. `PeerVisibleContractTest` pins the exact field allow-list. The aggregate builder reads through explicit adapters | #180, #183 |
| T9 | **Metadata exposure** (IP address, online times, social graph) | Documented in ADR §9 and shown to the user before enabling sharing. Contacts see IPs by necessity. No third party sees the graph. LAN discovery is opt-in and recognizable only to paired peers | #182, #187 |
| T10 | **DoS via huge or deep input** | The frame header is checked before reading (≤ 64 KiB). Every length and count is bounded before allocation. The schema is flat, with no recursion | #180 codec; #182 adds connection, rate, and cache quotas |
| T11 | **Malformed-input exploitation** (parser bugs, deserialization, code execution) | Hand-written strict reader. No Java serialization, reflection-driven binding, scripting, SQL, or URL fetching from peer data. Canonical re-encode check. `NoNetworkingContractTest` forbids `ObjectInputStream`, `Class.forName`, and `ScriptEngine` in the package | #180 |
| T12 | **Display spoofing** (bidi override, invisible characters, look-alike names) | NFC is required. Control, format, and bidi characters are rejected. UIs show the identity fingerprint next to names, and names are never unique keys | #180, #187 |
| T13 | **Stale or incompatible comparisons** presented as fair | Explicit windows labelled with zone and week start, partial-period cutoffs, equal-elapsed alignment, metric versions, profile fingerprint, scoring version, overall threshold for readiness verdicts, and sample minimums. `UNAVAILABLE` is never zero | #180 contracts, #185 |
| T14 | **Hidden central dependency** via library defaults (bootstrap lists, telemetry, update checks, OCSP/AIA fetches, DNS, NTP) | Zero new runtime dependencies in #180. IP-literal invitations only. JSSE revocation and AIA disabled. Every candidate library audited in the [inventory](runtime-dependency-inventory.md). Runtime egress test in #189 | #180 inventory, #182, #189 |
| T15 | **Revocation not honoured** by a peer that already has data | Cooperative tombstone with `requestCacheDeletion`. The UI must say that copies already received cannot be force-erased | #184, #187 |
| T16 | **Identity key theft** from disk or backup | Encrypted at rest and in backups (#181). Recovery: generate a new identity, re-pair, and tombstone the old transport bindings. There is no global revocation broadcast | #181 |
| T17 | **Clock skew or manipulation** | Five-minute future tolerance, receiver-side expiry, and no network time dependency. A skewed author is visible as `NOT_YET_VALID`, not silently accepted | #180 policy |
| T18 | **Unsupported future versions** crash or confuse old clients | `UNSUPPORTED_VERSION`, `UNSUPPORTED_MESSAGE_TYPE`, and `UNSUPPORTED_SCHEMA_VERSION` are safe drops. Unknown metrics are "not comparable" | #180 |

## 5. Residual risks (accepted and documented)

* Contacts learn your IP address and when you are online. Users are told this before they enable
  sharing.
* Scores are self-reported evidence. Competition is among people who choose to trust each other.
* Peers behind restrictive NAT or firewalls may never connect directly, and there is no hosted
  fallback.
* A lost identity without a backup cannot be recovered. A peer that already received data can keep
  it.
* Running one identity on two devices at once is unsupported and shows up as forks.
* The preparation-profile fingerprint reveals which bundled profile definition a peer is preparing
  against, but only when that peer has chosen to share the snapshot.

## 6. Verification hooks

* #180: golden fixtures, rejection matrix, acceptance policy tests, the peer-visible allow-list, and
  the no-networking, no-wiring, no-new-dependency guard (`src/test/java/com/codefit/peer/protocol`).
* #182: handshake tests with pinned and mismatched keys; tests showing the listener is off by
  default; an assertion that revocation and AIA are disabled.
* #189: an isolated two- and three-peer run with recorded egress. It must show traffic only to
  authorized peer addresses and LAN multicast, and no DNS, NTP, HTTP, or other destination.
