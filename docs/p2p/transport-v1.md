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
every socket (`hardenSocket`). A third, deliberately narrow use of the structural trust manager exists for
*rollover dials* only (§9): `TlsContexts.forRolloverDialer`. It is reachable only from
`PeerDialer.rolloverOnce`, after a pinned dial has already failed, and the connection it produces is
trusted solely on the strength of an identity-signed proof, never on the certificate.

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
   idempotent `DUPLICATE`, never `STALE_REVISION`/`FORKED`. When the local transport key changes (§9) the
   cached envelope is rebuilt for the new key with a higher revision and the next sequence number, for the
   same reason.

A live connection is accepted only when **all** of the following hold (`PeerSession`):

* the envelope decodes and its signature verifies (protocol v1, unchanged);
* the claimed author is a **`PAIRED`** local contact — `PENDING`, `BLOCKED`/`REMOVED`, or unknown all fail
  with a distinct reason (`NOT_PAIRED`, `UNKNOWN_IDENTITY`);
* `EnvelopeAcceptancePolicy` accepts it (or reports the idempotent `DUPLICATE`) against a per-author,
  in-process `AuthorReplayState` (protocol §10: replay state stays in memory until #184 persists it);
* the binding's `[validFrom, validUntil)` window covers `now`;
* the binding's `transportKey` equals the **live** TLS certificate's Ed25519 key on *this* socket — the
  step that actually ties "who signed this" to "who is on the other end of this specific connection";
* the binding is **not stale**: if a different key is already pinned for that contact, this binding's
  `validFrom` must be strictly later than the pinned binding's (`STALE_BINDING` otherwise, §9). The same key
  is always fine.

Any failure yields a specific `ConnectionFailureReason` (`UNKNOWN_IDENTITY`, `NOT_PAIRED`,
`BINDING_KEY_MISMATCH`, `BINDING_EXPIRED`, `BINDING_NOT_YET_VALID`, `ENVELOPE_REJECTED`, `STALE_BINDING`,
`ROLLOVER_REFUSED`,
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
or dials anything. The signature check itself lives in `InvitationCodec.verify(SignedInvitation)` — `decode`
calls it rather than duplicating it, and it is also the one place that check happens for a `SignedInvitation`
a caller constructs some other way: the record's own public constructor checks only the signature's
*length*, so a `SignedInvitation` is not by itself proof of anything, and every `NetworkingService` method
that mutates contact/trust/pinning state from one calls `InvitationCodec.verify` itself rather than assuming
the caller already decoded it (§9.1). Expiry/not-yet-valid are exposed as
`Invitation.isExpired`/`isNotYetValid` for the caller to check explicitly — "parsing an invitation alone must
not establish trust" is enforced by that separation, not by convention. `NetworkingService.registerPendingContactFromInvitation` is the explicit,
separate step that turns a parsed invitation into a `PENDING` contact (never `PAIRED` by itself), after
which the existing #181 `ContactService.acceptInvitation` is the user's explicit pairing action. It refuses
outright, creating nothing, when the invitation's identity is already a known contact in any trust state
(`KnownIdentityException`) — see §9.1 for the dedicated recovery flow that case routes to instead.

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

**What is announced is dialable.** The packet carries only the TCP listen port; the receiver pairs it with
the datagram's *source address*. That pair is reachable exactly when the TCP listener accepts connections
on that address (§8), so `LanDiscoveryService` announces and listens only on interfaces the listener's
`ListenerBindAddress` covers: every non-loopback, up, multicast-capable IPv4 interface for the wildcard;
only the interface owning the address for a specific bind; and it refuses to start at all for a
loopback-only listener, which no LAN peer could ever dial. Each datagram is sent out of the interface it
describes (`setNetworkInterface`), not whichever the OS defaults to, and the group is joined on each.
`NetworkingService.reachableAddresses()` / `createInvitation(passphrase, lifetime, now)` use the same
rule (`LocalAddresses`) to put real interface addresses and the real listening port into invitations.

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

(How the listener chooses what to bind to is §8; the "same LAN" case below depends on it.)

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

## 8. Where the listener listens (bind strategy)

The TCP listener's bind address is an explicit choice, `ListenerBindAddress`, passed to
`PeerNetworkService.enable` / `NetworkingService.enableNetworking`:

| Strategy | Reachable by | Use |
|---|---|---|
| `wildcard()` (**default**) | every interface on this machine, so real LAN peers | normal operation |
| `of(address)` | exactly that interface address | keep the listener off a VPN/public interface |
| `loopbackOnly()` | this machine only | tests, local tooling; LAN discovery refuses it |

A hardcoded loopback bind (what the first cut of #182 did) makes the listener unreachable from any other
device, while still passing every same-host test; `PeerListenerLanReachabilityTest` and the
`TwoProcessPeerDemoTest` LAN case therefore connect through this machine's real non-loopback interface
address, and assert a loopback-only listener refuses that same address.

**Reachability is not trust.** Binding wider changes who can *open a TCP connection*, not who is
*accepted*. Every inbound socket must still complete mutual TLS 1.3 and then the identity-signed
`IDENTITY_BINDING` exchange (§2) with a locally `PAIRED` contact, and until it does it is a bounded
(8 handshake threads, 16 queued, 15 s timeout, §5), anonymous, unauthenticated socket that is told nothing
(a failed or unknown caller learns only the listener's supported protocol versions from its `hello`, no identity or binding, and no error detail beyond the close).
Networking itself remains **off until `enableNetworking` is called**; there is no auto-start. Exposure that
remains and is not mitigated here: a wildcard listener on a host with a public address is reachable by
anyone on the internet for pre-authentication handshakes; there is no per-source-IP rate limit, only the
global bounds above. Use `of(address)` to avoid that.

The server socket is a plain TCP socket bound once; TLS is layered onto each accepted connection from the
*current key generation* (key, certificate and matching `PeerSession.Context` snapshotted together). That
is what allows §9's rotation to swap keys on the same port with no rebinding.

## 9. Transport-key lifecycle and rollover

**Keys and where they live.** The *local* transport key is one row in `transport_identity` (sealed with the
vault passphrase) plus, while networking is on, the key the listener presents. A *remote* contact's pinned
key is one row in `contact_transport_bindings` (`transportKey`, `validFrom`, `validUntil`). A transport key
is only ever authorized by an `IDENTITY_BINDING` signed with its owner's identity key; the identity key never
touches a socket.

**Local invariant.** The persisted key, the key the running listener presents, the key in every outgoing
`IDENTITY_BINDING`, and the key a new invitation advertises are always the same key. Everything that can
read or change the local key runs under one lock in `NetworkingService` (`enableNetworking`,
`createInvitation`, `refreshTransportKey`, `rotateTransportKey`) and goes through `syncLiveTransportKey`:
the persisted key is the source of truth, `TransportKeyService.ensureCurrent` may mint a new one (inside the
7-day renewal window, or if missing/expired), and if networking is on the live listener is switched to it
(`PeerNetworkService.rekey` -> `PeerListener.rekey`, same port, new connections only) *before* the key is
returned to anyone. `createInvitation` additionally refuses to advertise a key the listener is not presenting.
If persisting succeeds but switching fails, the next call re-converges (persisted != live -> rekey).
`enableNetworking` while already enabled is idempotent (no second writer session). A new key's `validFrom` is
whole seconds and strictly later than its predecessor's (`TransportKeyService`), and the binding envelope's
`revision` is that `validFrom` in epoch seconds, so receivers order rollovers and never see a reused
revision (`STALE_REVISION`) or sequence (`FORKED`) for a legitimately new key. The vault passphrase is not
kept in memory, so renewal happens only on a passphrase-bearing call; a long-running session should call
`refreshTransportKey` periodically.

**Remote pin: only forward, only on proof.** The pinned key for a contact changes only when a connection
*fully authenticates* (every check in §2) and `ContactService.recordAuthenticatedTransportBinding` finds the
binding is the same key (refresh) or a different key whose `validFrom` is strictly later (rollover); older
bindings are ignored. The transport reports each such binding to `NetworkingService.onVerifiedBinding`
for inbound connections (the listener) and outbound ones (`connectToContact`) alike, before the connection
is handed out. Ordinary invitation import can never touch an existing contact's pin or trust state:
`registerPendingContactFromInvitation` refuses outright (`KnownIdentityException`, nothing mutated) for an
identity that is already a known contact in any trust state. The one deliberate exception is the explicit
out-of-band recovery in §9.1, which is a separate action an existing `PAIRED` contact's own user must invoke,
never an automatic side effect of parsing or importing an invitation.

**State flow when Bob rotates and Alice still pins the old key (K1 -> K2):**

1. Alice dials Bob pinned to K1. Bob's listener presents K2, so Alice's `PinnedTransportTrustManager`
   refuses it: `WRONG_PIN`, before any application byte. Nothing was disclosed and nothing changed.
2. Because the contact is `PAIRED` and a pin exists, Alice makes **one** rollover attempt
   (`PeerDialer.rolloverOnce`): TLS accepts any structurally valid certificate (nothing is trusted yet) and
   Alice sends a hello with magic `CFR1` (same layout as `CFH1`) and **nothing else**.
3. Bob's listener sees the rollover hello. It learns whom to address from Alice's *TLS-proven client key*,
   not from any claim: that key must be the key it has pinned for a `PAIRED` contact
   (`KnownContactLookup.pairedContactPinnedTo`). If not, it closes with nothing disclosed (Alice sees
   `ROLLOVER_REFUSED`). Otherwise Bob sends his `IDENTITY_BINDING` for K2 first, addressed to that contact.
4. Alice accepts only if: authored by the identity she dialed; signature, audience, and replay policy pass;
   the contact is `PAIRED`; the window covers now; `transportKey` equals the live K2 on this socket; K2 is
   not the pinned key; and the binding's `validFrom` is strictly later than the pinned binding's
   (else `STALE_BINDING`). Only then does Alice send her own binding.
5. Bob validates Alice exactly like an inbound caller and requires her binding to be authored by the
   contact her TLS key was pinned for. Both sides report the verified binding; Alice's pin moves to K2 (and
   Bob refreshes his view of Alice). The next dial is an ordinary pinned dial against K2.

If Bob dials Alice instead, Alice's listener (structural TLS) validates Bob's K2 binding like any inbound
caller; it is adopted because it is identity-signed and strictly newer than the pin, not because TLS
accepted the key.

**What is refused (pin unchanged, nothing persisted):** a stranger or a different identity at the address
(`IDENTITY_MISMATCH` / `UNKNOWN_IDENTITY`; the dialer has disclosed nothing); a genuine binding replayed over
someone else's TLS key (`BINDING_KEY_MISMATCH`); a genuine but *older* binding or a superseded key
(`STALE_BINDING`), including the case where the old key's holder dials in after the pin has moved on; an
expired or not-yet-valid binding; and a peer that will not run the proof (`ROLLOVER_REFUSED`).

**Known limits.** (1) *Both* peers rotating before reconnecting leaves neither able to identify the other's
new TLS key over the wire, so the live rollover handshake is refused (`ROLLOVER_REFUSED`) rather than
disclosing first; §9.1 is the out-of-band recovery for exactly this case. Both sides renew inside the same
7-day window if paired on the same day, so this is plausible, though each side learns the other's key at any
connection that happens in between. (2) A transport key whose holder is compromised stays acceptable to peers
that pin it until its binding expires or a newer binding reaches them; revocation (`Tombstone` of the
binding) is not delivered by this slice. (3) A device restored from an old backup presents an older binding
and is refused as stale until it mints a new key. (4) Replay state is in memory, so a restart relies on the
new writer epoch superseding the old one.

### 9.1 Out-of-band recovery when both sides have rotated

The live rollover handshake (above) only ever resolves a rotation on *one* side, because it works by one
peer recognizing the *other's unchanged* TLS certificate key as something it already has pinned. When both
sides rotated before reconnecting, neither TLS key is recognizable to the other any more, and the protocol
correctly refuses rather than disclose an identity binding to an unrecognized caller. Recovering from this
needs a channel outside the live connection — the same one first pairing already used: the contacts exchange
a fresh invitation (string, QR code, or whatever out-of-band channel they trust) and each side explicitly
re-verifies and recovers the other's new key via `NetworkingService.recoverContactTransportKey(contactId,
signedInvitation, now)`.

This is a deliberate, separate action from `registerPendingContactFromInvitation`, not something an
ordinary re-import of an invitation ever triggers automatically:

* **It verifies the invitation's signature itself, first, before any other check.** `SignedInvitation`'s
  own (public, record-generated) constructor checks only that the signature is the right *length* — it is
  not proof of anything, and nothing in the type system stops a caller from constructing one directly with
  an arbitrary `Invitation` (including one naming the correct contact's real, public identity key) and an
  arbitrary 64-byte value. This method never assumes `signed` already passed through `InvitationCodec.decode`
  or `parseInvitationBase64`; it calls the now-shared `InvitationCodec.verify(signed)` itself
  (`InvitationRejectionReason.BAD_SIGNATURE` on failure) before looking at a single field of the invitation,
  so a forged invitation — a genuine identity key with a fabricated signature, or a genuinely-signed
  invitation with any field (transport key, binding window) subsequently altered — never reaches any later
  check, and never has a chance to poison the pin before the pin-monotonicity check ever runs.
* It never creates a contact. The caller supplies the existing `contactId` explicitly — this method never
  searches for or infers one from the invitation — so the UI flow that calls it has already shown the user
  the identity fingerprint for the same out-of-band re-verification first pairing required.
* It refuses outright, mutating nothing, when: the contact is not currently `PAIRED` (a `BLOCKED` or
  `REMOVED` contact must go through the existing, explicit `ContactService.rePair` first — its own
  deliberate act of restored trust, so a blocked or removed contact can never silently regain trust through
  this path); the (now cryptographically verified) invitation identity is not *this contact's own* pinned
  identity key (`IdentityMismatchException` — a validly-signed invitation from a different identity is
  never silently attributed to this contact); or the invitation itself, or its declared transport-key
  binding window, is not currently valid (`InvitationException`).
* Beyond those checks, the pinned key only ever moves forward through the exact same rule as a live
  rollover proof (`ContactService.recordAuthenticatedTransportBinding`): the same key refreshes its window, a
  different key replaces the pin only when its binding is strictly newer, and a stale or replayed invitation
  carrying an older binding is silently ignored (`IGNORED_STALE`) rather than rolling the contact back.
* It never itself authenticates a connection or trusts an arbitrary new TLS key onto the wire: it only
  updates what a *future* connection's live binding will be checked against. The next dial or accept still
  runs every check in §2, including that the live certificate's key matches the newly recovered pin.
* Contact id, alias, cached display name, address cache, sharing permissions, consent revisions, and history
  are never touched.

`registerPendingContactFromInvitation` is the companion half, and enforces the identical invariant: it too
calls `InvitationCodec.verify` itself, first, rather than assuming the caller already decoded `signed` —
a manually constructed `SignedInvitation` with a forged or missing signature is refused
(`InvitationRejectionReason.BAD_SIGNATURE`) before it can create anything. It separately refuses outright
(`KnownIdentityException`, carrying the existing contact's id and trust state) when the (verified)
invitation's identity is already a known contact in any trust state, rather than raising a bare "already a
known contact" error with nowhere to go. A caller that catches it routes to `recoverContactTransportKey` for
a `PAIRED` contact, or to `ContactService.rePair` first for a `BLOCKED`/`REMOVED` one.

**The invariant, stated once:** any service method that mutates contact, trust, or pinning state from a
caller-supplied `SignedInvitation` must cryptographically verify it itself (`InvitationCodec.verify`, which
`decode`/`fromBase64` also call rather than duplicating the check) before trusting anything in it — never by
assuming the caller already went through the decode path. `registerPendingContactFromInvitation` and
`recoverContactTransportKey` are, at the time of writing, the only two methods in
`com.codefit.service.NetworkingService` that accept a caller-supplied `SignedInvitation`
(`createInvitation` only ever *produces* a genuinely signed one via `InvitationCodec.sign`, and
`parseInvitationBase64` takes a `String` and already goes through `fromBase64`); both were audited and now
hold the invariant.

Verified by `TransportKeyRecoveryTest`: the refused-rollover-then-recovered-then-reconnected round trip,
staleness/replay is ignored without rolling the pin back, an invitation signed by a different identity is
refused, a blocked/removed contact is refused until re-paired, a fresh invitation for an already-known
identity never creates a duplicate contact, an invitation whose declared binding window does not cover
`now` is refused, a manually constructed invitation carrying a genuine identity key but a random signature
is rejected (`BAD_SIGNATURE`) with the pin, contact state, and permissions all left untouched, a genuinely
signed invitation subsequently tampered (transport key, or binding window) under the original signature is
likewise rejected before the tampered values can reach the pin-monotonicity check, a genuinely signed fresh
invitation still recovers successfully, `registerPendingContactFromInvitation` itself rejects the same kind
of forged invitation without creating a contact, and — using two fully independent `NetworkingService`
instances, each over its own
database — that the method has no built-in direction: whichever paired identity calls it, it is the exact
same code path.

