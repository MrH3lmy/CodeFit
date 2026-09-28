# CodeFit peer transport v1 (#182)

Status: **implemented, #182**. Builds on [ADR-0001](adr-0001-decentralized-peer-architecture.md) §5-§6
and [protocol v1](protocol-v1.md), which this document does not restate or modify — protocol v1's frame,
envelope, and message bytes are unchanged. This document is the concrete reference for what #182 added:
`com.codefit.peer.transport`, `com.codefit.peer.invitation`, and `com.codefit.peer.discovery`.

## 1. Transport selection, as built

Plain TCP, JDK JSSE `TLSv1.3`, mutual authentication, self-signed Ed25519 certificates
(`SelfSignedCertificateFactory`, a small in-repo DER encoder — no Bouncy Castle needed). Trust is never
"trust-all": two purpose-built `X509TrustManager`s exist, and neither is a chain-validating PKIX manager.

* **`PinnedTransportTrustManager`** (used when *dialing*): rejects any certificate whose Ed25519 key is
  not byte-for-byte the caller's already-expected transport key. A wrong pin fails the TLS handshake
  itself, before any application data is exchanged.
* **`StructuralTransportTrustManager`** (used by the *listener*): a listener cannot know in advance which
  paired contact will call next, so it cannot pin one key. It still rejects anything that is not exactly
  one well-formed, self-consistent, currently-valid, self-signed Ed25519 certificate. This is a real
  structural check, not a rubber stamp — the actual identity decision happens one layer up (§2).

`com.sun.net.ssl.checkRevocation` and `com.sun.security.enableAIAcaIssuers` are explicitly set to
`false` at `SSLContext` construction time (`TlsContexts`), and `TLSv1.3` is the only enabled protocol on
every socket (`hardenSocket`/`hardenServerSocket`).

## 2. Post-handshake authentication: hello + `IDENTITY_BINDING`

Mutual TLS alone proves the peer holds the private key for *some* certificate; it says nothing about
which identity authorized that key. So a TLS handshake completing is **not** authentication — it only
unlocks a private channel over which the real proof travels. This is the mechanism behind "invitation
parsing alone", and now "TLS handshake alone", must never establish trust.

Immediately after the TLS handshake, both sides exchange, over the now-encrypted stream:

1. **A `hello` frame** (`HandshakeIo`, `com.codefit.peer.transport`, distinct from protocol v1 envelope
   frames and never itself an envelope): `magic "CFH1" (4 bytes)`, `count (1 byte, 1..8)`,
   `count × major-version (1 byte each)`. Both sides send their own hello without waiting to read the
   other's first (a few bytes always fit in the OS socket buffer, so there is no deadlock). No overlap
   between the two hello lists' major versions is `INCOMPATIBLE_VERSION`, closed without further
   exchange. This is the "initial hello exchange that lists supported major versions" protocol v1 §12
   already anticipated; it does not change any envelope byte.
2. **A protocol v1 `IDENTITY_BINDING` envelope**, addressed `DIRECT` to the other party, using the
   existing `EnvelopeCodec`/`EnvelopeAcceptancePolicy` unchanged. Whoever dialed sends theirs first
   (addressed to the identity they intended to call); the listener cannot address its own reply until it
   has learned the caller's identity from that first envelope, so it always answers second. Each device
   caches and resends the *identical* signed bytes across reconnects within one writer-session epoch
   (`LocalBindingEnvelopeCache`), so a reconnect within the epoch is reported as the protocol's own
   idempotent `DUPLICATE`, never `STALE_REVISION`/`FORKED`.

A live connection is accepted only when **all** of the following hold (`PeerSession`):

* the envelope decodes and its signature verifies (protocol v1, unchanged);
* the claimed author is a **`PAIRED`** local contact — `PENDING`, `BLOCKED`/`REMOVED`, or unknown all fail
  with a distinct reason (`NOT_PAIRED`, `UNKNOWN_IDENTITY`);
* `EnvelopeAcceptancePolicy` accepts it (or reports the idempotent `DUPLICATE`) against a per-author,
  in-process `AuthorReplayState` (protocol §10: replay state stays in memory until #184 persists it);
* the binding's `[validFrom, validUntil)` window covers `now`;
* the binding's `transportKey` equals the **live** TLS certificate's Ed25519 key on *this* socket — the
  step that actually ties "who signed this" to "who is on the other end of this specific connection".

Any failure yields a specific `ConnectionFailureReason` (`UNKNOWN_IDENTITY`, `NOT_PAIRED`,
`BINDING_KEY_MISMATCH`, `BINDING_EXPIRED`, `BINDING_NOT_YET_VALID`, `ENVELOPE_REJECTED`,
`UNSUPPORTED_VERSION`, `MALFORMED_FRAME`, ...), never a bare boolean.

## 3. Invitation format (own signing context)

An invitation is a separate signed structure, not a wrapped envelope, with its own domain-separated
signing context (`"CodeFit-P2P-Invitation-v1\0"`, distinct from `"CodeFit-P2P-Envelope-v1"`) so the two
can never be confused. Bytes (`InvitationCodec`), all bounds checked before allocation:

```
u8         formatVersion        (1)
bytes[32]  identityKey
bytes[32]  transportKey         (must differ from identityKey)
i64        bindingValidFrom     (epoch millis)
i64        bindingValidUntil    (> bindingValidFrom)
u8         addressCount         (1..4)
  per address:
  u8         hostLength         (1..45, an IP-literal length; never a hostname)
  bytes      host               (ASCII)
  u16        port
bytes[16]  nonce
i64        issuedAt
i64        expiresAt            (> issuedAt, lifetime ≤ 30 days)
bytes[64]  signature            (Ed25519, over everything above, by identityKey)
```

Decoding (`InvitationCodec.decode`) only parses and verifies the signature; it never registers a contact
or dials anything. Expiry/not-yet-valid are exposed as `Invitation.isExpired`/`isNotYetValid` for the
caller to check explicitly — "parsing an invitation alone must not establish trust" is enforced by that
separation, not by convention. `NetworkingService.registerPendingContactFromInvitation` is the explicit,
separate step that turns a parsed invitation into a `PENDING` contact (never `PAIRED` by itself), after
which the existing #181 `ContactService.acceptInvitation` is the user's explicit pairing action.

## 4. LAN discovery: recognition without cleartext identity

Off by default; started only by `NetworkingService.enableLanDiscovery`. Announcements go to the
administratively-scoped (site-local, RFC 2365) multicast group `239.192.42.99:52735` — never routed off
the LAN by a compliant router. The wire packet (`LanAnnouncementCodec`, fixed 47 bytes) carries no
identity in the clear:

```
magic "CFLD" (4) | version (1) | timeSlot i64 (8) | listenPort u16 (2) | tag bytes[32]
```

`tag = HMAC-SHA256(recognitionKey, "CodeFit-LAN-Discovery-v1\0" || timeSlot || senderIdentityId)`, where
`recognitionKey = SHA-256(min(idA, idB) || max(idA, idB))`. Both `recognitionKey` and `senderIdentityId`
are computed locally from identity keys each already-paired side already possesses; neither travels on
the wire. A stranger who is not paired with either device cannot compute a matching tag and therefore
cannot tell which identity (if any) is behind an announcement it observes — it only learns "some device
at this address announced, and when" (ADR-0001 §4/§9). Folding the sender's own identity into the tag
(rather than only the unordered pair) is deliberate: without it, a device could mistake its own
announcement, echoed back over loopback or a multicast-reflecting switch, for a real announcement from
the very contact it was announcing itself to (`LanAnnouncementCodecTest` pins this down).

`timeSlot` is a 30-second bucket; a receiver accepts the current slot and one slot either side, tolerating
modest clock skew without a network time dependency.

## 5. Bounds, limits, and states

| What | Bound | Enforced by |
|---|---|---|
| Frame/body size | protocol v1's existing 64 KiB / 60 KiB, checked before allocation | `EnvelopeCodec`, reused unchanged |
| Hello version list | 1..8 entries | `HandshakeIo` |
| Invitation addresses | 1..4 | `Invitation` |
| Invitation lifetime | ≤ 30 days | `Invitation` |
| Cached addresses per contact | 8, oldest-by-last-seen evicted | `ContactAddress`/`ContactAddressRepository` |
| Concurrent in-flight handshakes (listener) | 8 threads, 16 queued; excess is refused immediately | `PeerListener` |
| Authenticated inbound connections | 32 | `PeerListener` (a `Semaphore` acquired *before* the handshake starts, so a full listener never even attempts one over the limit) |
| Concurrent outbound dials | 4 | `PeerNetworkService` |
| Per-attempt connect/handshake timeout | 8 s connect, 15 s handshake (production defaults; test-only overloads exist) | `PeerDialer` |
| Retry attempts | caller-supplied `RetryPolicy` (bounded attempts, exponential backoff capped, jittered) | `PeerDialer.dialWithRetry` |

Connection states (`ConnectionState`): `QUEUED`, `CONNECTING`, `AUTHENTICATING`, `CONNECTED`, `CLOSED`,
`UNREACHABLE`, `INCOMPATIBLE_VERSION`, `REJECTED`, `CANCELLED`, `RETRY_WAITING`. Failure reasons
(`ConnectionFailureReason`) are always attached — nothing surfaces as a bare `false`.

## 6. IPv4/IPv6 and router/firewall constraints

Direct TCP works when both peers are on the same LAN, or when the *listening* peer is otherwise reachable
(a public IPv4 address, a manual port-forward, or IPv6 without an inbound block). `PeerAddress` accepts
both IPv4 and IPv6 literals (an IPv6 literal is written bracketed, `[2001:db8::1]:port`, matching URI
convention) and never a hostname — there is no DNS lookup anywhere in this package
(`NoHiddenServiceDependencyTest` pins this for invitation parsing; `PeerAddress`'s own literal-only
validation pins it generally). CodeFit does **not**:

* create router port mappings automatically (no UPnP-IGD/NAT-PMP client exists in #182 — ADR-0001 §6
  leaves that as a possible, explicit, opt-in future addition, never silent);
* attempt any form of NAT traversal, hole punching, or relay;
* fall back to a hosted service when a peer is unreachable.

An unreachable peer surfaces as `ConnectionState.UNREACHABLE` with a specific `ConnectionFailureReason`
(`CONNECT_TIMEOUT`, `CONNECTION_REFUSED`, or `NETWORK_UNREACHABLE`) after the configured retries are
exhausted — a recoverable local state, never a crash, and never a hosted fallback.

## 7. Lifecycle and shutdown

Nothing in `com.codefit.peer.transport`/`invitation`/`discovery` runs unless explicitly started.
`PeerNetworkService` (the package's only public entry point besides value types) opens no socket until
`enable(...)`; every thread it starts (`PeerListener`'s accept loop and handshake pool, `PeerDialer`'s
dial pool) is a daemon thread, matching ADR-0001 §5. `disable()`/`close()` stop the listener, cancel
dialing, close every tracked connection, and forget key material — safe to call whether or not the
service was ever enabled, and verified in `PeerNetworkServiceTest` by confirming a fixed port can be
rebound immediately afterward (proof the OS socket was actually released, not merely dereferenced).
`LanDiscoveryService` follows the same pattern, one level up in `NetworkingService`.
