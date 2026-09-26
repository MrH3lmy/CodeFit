# ADR-0001: Fully decentralized peer profiles, comparisons, and competition

* **Status:** Accepted (foundation slice #180 of epic #179)
* **Date:** 2026-09-26
* **Related:** [protocol v1](protocol-v1.md) · [threat model & data flows](threat-model.md) ·
  [runtime dependency inventory](runtime-dependency-inventory.md)

## Context

Epic #179 adds social profiles, competition, daily and weekly comparisons, preparation comparisons,
and personal history to CodeFit, a single-user Java 21 + JavaFX + SQLite desktop app. The owner's
**hard constraint** is *no centrally operated service at all*. The following are all excluded:

* third-party or self-hosted login, profile databases, and leaderboards;
* signaling and matchmaking services, hosted STUN/TURN relays, and mandatory bootstrap nodes;
* default DHT peers and public gateways, cloud mailboxes and storage, and telemetry.

A self-hosted central server also fails the requirement. Participant devices are the network. When
connectivity cannot be achieved without a central party, the correct result is a documented
limitation, not a fallback.

Relevant facts from the repository on `main` (`519edea`):

* `InterviewPreparationProfile` is a company/role competency definition.
* `InterviewReadinessService` computes readiness at read time and nothing is persisted, so there is
  no history.
* Readiness has coverage and critical-gate semantics that must survive sharing.
* `ReviewHistory.isObjectivelyCorrect()` falls back to self-rating for legacy rows.
* Legacy timestamps are stored without offsets. Some columns hold UTC from SQLite
  `CURRENT_TIMESTAMP`; others hold local time from Java `LocalDateTime`.
* `docs/problem-solving-source-attribution.md` forbids exporting imported workbook content, problem
  text, answers, code, notes, tokens, keys, or the database.

## Decision summary

| Concern | Decision | Delivered by |
|---|---|---|
| Identity | Locally generated **Ed25519** identity key (JDK `SunEC`). `IdentityId = SHA-256(pubkey)`. Display names are self-asserted labels | #181 |
| Device/transport key | A separate Ed25519 transport key, authorized by a signed `IDENTITY_BINDING` | #181, #182 |
| Local storage | Existing SQLite via `DatabaseConfig`/`SchemaMigrator`. The private learning database is never replicated | #181, #183, #184 |
| Invitation/bootstrap | Out-of-band, signed, expiring invitation carrying identity key, transport binding, and 1–4 **IP-literal** addresses | #182 |
| Discovery | Explicit invitation or a known address. Optional, opt-in LAN discovery. No DHT, no bootstrap list | #182 |
| Secure transport | **TCP + JDK JSSE TLS 1.3 with mutual authentication** using self-signed Ed25519 transport certificates, pinned to the bound transport key. No CA, no revocation fetching | #182 |
| Wire format | Bounded frames with deterministic binary envelopes and Ed25519 signatures ([protocol v1](protocol-v1.md)) | **#180 (this)** |
| Selective summaries | Per-recipient consent revisions. Aggregate-only, signed `PROGRESS_SUMMARY` and `PREPARATION_SNAPSHOT` | #183, #184 |
| Comparisons | Computed on each device from cached, signed summaries, with explicit windows, provenance, and freshness | #185 |
| Group state | Signed challenge manifest with an explicit member list. Each device ranks the same record set deterministically | #186 |
| Forwarding | Optional, opt-in, encrypted store-and-forward through consenting participant devices (HPKE; library chosen in #188) | #188 |
| Networking in #180 | **None.** Pure contracts and codec. `NoNetworkingContractTest` guards this | #180 |

## 1. Identity

* A local Ed25519 key pair *is* the identity. It is created by the user in #181, on demand, never
  implicitly. This slice generates no production identity. `IdentityId` (SHA-256 of the 32-byte
  public key) is what audiences and contacts reference.
* The **transport key** is a separate Ed25519 key used in the device's TLS certificate. The identity
  key signs an `IDENTITY_BINDING` over `(transportKey, validFrom, validUntil)`. This lets the
  transport key rotate, and lets a revoked binding be tombstoned, without changing identity.
* Display names are not unique or verified. Contacts are pinned by `IdentityId` at pairing time. UIs
  show a short fingerprint so users can compare it out of band (format fixed in #181).
* **Backup and restore (#181).** The encrypted identity export contains the identity private key and
  the `(epoch, nextSequence)` counter. The candidate at-rest protection is JDK
  `PBKDF2WithHmacSHA256` with `AES/GCM/NoPadding`, both present in the JDK 21 `SunJCE` provider
  (verified locally). Parameters are fixed in #181. Restore **increments the epoch** before the
  first publication, so a sequence rolled back from an older backup never collides (see protocol
  §10).
* **Single active signing writer per identity is an explicit MVP limitation.** Two concurrent
  installs sharing one identity are detected by peers as `FORKED`. They are not supported.
* A signature means *published by this key*, never *earned honestly*. There is no anti-cheat,
  one-human-one-account guarantee, or global completeness.

## 2. Social profile vs. interview-preparation profile

`SocialProfileCard` (display name, optional bio, comparison zone, week start) says who a peer claims
to be and how they want periods compared. `InterviewPreparationProfile` stays exactly what it is: a
competency definition. The two never reference each other. Preparation comparisons use a
`PREPARATION_SNAPSHOT` that names a profile by id plus a **definition fingerprint** and a **scoring
version**. `PeerVisibleContractTest` fails if any wire type references `com.codefit.model` or
`com.codefit.service`.

## 3. Local storage

Nothing in this slice touches the database. Later slices add tables through `SchemaMigrator`: local
identity (encrypted), contacts and pins, consent revisions, a cache of received envelopes, replay
state, and dated readiness snapshots. Tests use `IsolatedDatabaseExtension` and never touch a
learner's `codefit.db`. Received data is stored as verified envelope bytes plus indexed fields. A
peer's data is never merged into the learner's own learning tables.

**Historical snapshots.** Readiness is computed at read time, so history begins when #183 starts
saving dated snapshots. Past readiness that was never captured is `UNAVAILABLE`, never reconstructed.

## 4. Invitation, bootstrap, and discovery

* **A peer ID alone cannot locate anyone.** First contact always needs a reachable address that
  users exchange out of band: a copied string, a QR code, or any channel *the users* choose. CodeFit
  operates no channel.
* The invitation (#182) is a signed, expiring blob. It carries the identity key, the current
  `IDENTITY_BINDING`, 1–4 `IP:port` literals (IPv4 or IPv6), a one-time nonce, and an expiry. It has
  its own signature context, distinct from envelopes. The product performs **no DNS lookups**
  (hostnames are not accepted in v1), so no resolver service is implied.
* **Optional LAN discovery (#182)** is off by default. If enabled, the device sends link-local
  multicast announcements that only already-paired contacts can recognize (for example a MAC over a
  time slot keyed by a pairwise secret). Strangers on the LAN still learn that some device at that
  address announces, and when.
* Introductions happen only through participating peers, with explicit consent, and give no node a
  privileged role.
* Rejected alternatives: Kademlia or other public DHTs and IPFS, which need bootstrap peers or
  public gateways; Tor or I2P onion routing, which depends on directory authorities or seed
  infrastructure; WebRTC, which in practice needs STUN/TURN and signaling.

## 5. Secure transport and library selection

**Selected: plain TCP plus JDK JSSE TLS 1.3, mutual authentication, Ed25519 self-signed transport
certificates, and a custom `X509TrustManager` that pins the certificate key to the peer's bound
transport key.** Frames from protocol v1 travel inside the TLS stream. This is the approach
Syncthing proved viable (device identity = certificate), without Syncthing's optional global
discovery and relay servers.

Evidence, checked against current primary sources and on the local toolchain (OpenJDK 21.0.10):

* The Java SE 21 *Security Standard Algorithm Names* document lists `Ed25519` (Signature,
  KeyPairGenerator), `X25519` (KeyAgreement), `ChaCha20-Poly1305`, `TLSv1.3`, the TLS signature
  scheme `ed25519`, and the named group `x25519`.
  (<https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html>)
* A throwaway loopback probe, not committed, used two `keytool -keyalg Ed25519` self-signed
  certificates. It completed a **mutual TLS 1.3** handshake (`TLS_AES_256_GCM_SHA384`) with a
  key-pinning trust manager, and the mismatched pin was **rejected**. `SunEC` resolves from the
  `jdk.crypto.ec` module on JDK 21, so a jlink image must include it.
* JDK 21 has **no public HKDF or HPKE API**. The KDF API was a preview in JDK 24 (JEP 478) and final
  in JDK 25 (JEP 510; <https://openjdk.org/jeps/510>). This does not matter for the TLS transport,
  but it rules out a hand-built Noise handshake on the bare JDK. The project will not invent
  cryptography.
* JSSE revocation checking (`com.sun.net.ssl.checkRevocation`) and AIA fetching
  (`com.sun.security.enableAIAcaIssuers`) are unset by default. With a custom trust manager no
  OCSP/CRL/AIA fetch occurs, and #182 sets both explicitly to `false`.
* JDK 21 has no public X.509 *builder*. #182 therefore either writes a small DER encoder for one
  fixed self-signed certificate template (encoding only; signing uses JDK Ed25519) or adds Bouncy
  Castle `bcpkix-jdk18on` 1.86 (latest on Maven Central as of 2026-09-26; MIT-style Bouncy Castle licence).
  The default preference is the zero-dependency option. Either way the choice is recorded in the
  dependency inventory first.
* Shutdown: blocking sockets on daemon threads, closed from `Application.stop()`. There is no native
  code, no background service, and nothing listens unless the user enables sharing.

**Evaluated and not selected for the MVP: jvm-libp2p** (<https://github.com/libp2p/jvm-libp2p>).

* It is written in Kotlin, dual MIT/Apache-2.0, maintained (v1.3.7, 2026-09-14), JDK 11+, and used
  by Teku.
* Artifacts are published to a Cloudsmith Maven repository and JitPack, not Maven Central.
* Its build pulls Netty 4.2, protobuf, Bouncy Castle, `tech.pegasys:noise-java`, and **native**
  `netty-codec-native-quic` and `netty-tcnative-boringssl-static` binaries for five OS/arch targets.
* The README marks yamux, TLS, QUIC, circuit relay v2, AutoNAT, and mDNS as beta.
* It would add significant packaging weight and several default-bearing subsystems (DHT, relay, and
  identify) that we would have to audit and disable. Meanwhile it adds nothing the direct-address
  MVP needs that JSSE lacks.
* libp2p concepts (peer-assisted relay and hole punching) remain the reference design for #188. If
  #188 needs them, jvm-libp2p will be re-evaluated then as a bounded feasibility spike, with every
  default audited, not assumed.

## 6. Connectivity: accepted limitations

Direct TCP works when the peers share a LAN, or when the listening peer is reachable. That means a
public IPv4 address, a manual port-forward, or IPv6 without an inbound block. In every other case
the peer is **unreachable**. The UI shows a recoverable state ("last seen", cached results with
freshness, "ask your contact to connect to you or share a reachable address"). There is **no hosted
fallback**.

Possible later, peer-only improvements:

* opt-in UPnP-IGD/NAT-PMP port mapping, which talks only to the user's own router;
* forwarding through a mutually reachable, consenting participant (#188).

No participant may become a required hub. Participation never promises universal connectivity or
continuous availability.

## 7. Selective summaries, comparisons, and group state

* A **per-recipient** `CONSENT_REVISION` names the complete set of scopes shared with that contact.
  Revocation is a new revision with fewer scopes. Earlier copies can only be asked to be deleted
  (cooperative tombstone).
* Only aggregates travel. `PROGRESS_SUMMARY` carries versioned integer metrics with sample size,
  **provenance** (verified vs. self-rated vs. legacy fallback vs. learner-reported vs. timer vs. mock
  self-score), and **timestamp basis** (exact UTC vs. legacy assumptions). `PREPARATION_SNAPSHOT`
  preserves coverage and critical gates, and the receiver re-derives its status.
* **Comparisons are local.** Each device compares cached, verified summaries. It uses explicit UTC
  windows labelled with zone and week start, partial-period cutoffs, equal-elapsed alignment,
  minimum sample sizes, and profile-fingerprint and scoring-version compatibility (protocol §7–§9).
  Missing data is `UNAVAILABLE` or `INSUFFICIENT_DATA`, never zero.
* **Group challenges** (#186) are a signed manifest with an explicit member list and a GROUP
  audience. Every member ranks the same accepted record set with integer arithmetic and a fixed
  tie-break, so all devices agree.

## 8. Optional encrypted forwarding (#188)

A consenting participant device may hold an opaque `FORWARDED_CIPHERTEXT` for an offline recipient.
The ciphertext is sealed to the recipient with HPKE (RFC 9180) and carries a quota, a retention
limit, a hop limit, and a recipient allow-list. The library is chosen in #188 from Tink 1.23.0
(Apache-2.0) or Bouncy Castle 1.86, because JDK 21 lacks HPKE. Delivery while the author is offline
requires *some* reachable holder of a copy. That is a property of the participants, not a service.

## 9. What each participant can learn

| Observer | Learns | Does not learn |
|---|---|---|
| **Paired contact** (direct TLS peer) | Your IP address and port, when and how often you connect, when you are online, TLS certificate and identity key, protocol version, and exactly the scopes you shared with them (summaries, profile card, snapshots) | Unshared scopes, your learning database, raw content, other contacts' data (unless it is a shared group audience) |
| **Group/challenge member** | The member list (identity ids), each member's shared challenge metric, and IPs of members they connect to directly | Scopes not shared with the group |
| **Forwarding peer** (#188, opt-in) | Who handed it the ciphertext, the final recipient's `IdentityId`, ciphertext size, and timing and expiry | Plaintext content or the inner author signature contents |
| **LAN observer** (only if LAN discovery is enabled) | That a device at an IP announces on the LAN, and when; announcement size | Identity or contacts (announcements are recognizable only to paired peers) |
| **Network path / ISP** | Peer IP pairs, connection timing, volume, and that TLS 1.3 is in use | Content, identities (TLS 1.3 encrypts certificates) |
| **Invitation channel** (whatever users pick) | Invitation contents: identity key, transport binding, IP addresses, expiry | Anything after pairing |
| **CodeFit authors/maintainers** | **Nothing.** There is no telemetry and no service | — |

## Consequences

* Positive: there is no operator, no account, and no infrastructure to trust, run, or subpoena. The
  stack is unchanged (no new runtime dependency in #180; possibly none in #182). The wire format is
  executable and pinned by golden fixtures before any socket exists.
* Negative, and accepted: some peers will never connect directly, and there is no universal
  reachability. Availability of shared data depends on the peers being online. Identity loss without
  a backup is unrecoverable. Two devices cannot share one identity concurrently. Scores are
  self-reported evidence.
* The protocol is binary, not JSON. A human reads the golden fixtures through the spec tables, not
  by eye.

## Scope of #180

This slice delivers the ADR, the [protocol specification](protocol-v1.md), the
[threat model](threat-model.md), the [dependency inventory](runtime-dependency-inventory.md), and
`com.codefit.peer.protocol`. That package contains the value types, canonical codec, signature
verification, acceptance policy, time windows, metric registry, profile fingerprint, and the
readiness-snapshot adapter, plus conformance tests. It does **not** include key persistence,
identity generation, a listener, egress, UI, a scoring engine, or forwarding. The package is not
referenced by any other production code.

**Next dependency-ready issue:** #181, which depends only on #180. It adds local identity, social
profiles, contacts, and sharing permissions.
