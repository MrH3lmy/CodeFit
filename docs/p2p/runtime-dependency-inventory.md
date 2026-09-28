# Runtime endpoint and dependency inventory: peer features

This is the authoritative list of **what CodeFit may contact at runtime** and **which libraries the
peer features may use**. Any change needs an update here first, in the same PR, with primary-source
evidence. Related: [ADR-0001](adr-0001-decentralized-peer-architecture.md) ·
[threat model](threat-model.md) · [protocol v1](protocol-v1.md).

## 1. Runtime endpoints

| Endpoint | Allowed? | Condition | Introduced by |
|---|---|---|---|
| Any network endpoint | **No** by default | Nothing opens until `NetworkingService.enableNetworking` is called; `NoHiddenServiceDependencyTest`/`PeerNetworkServiceTest.isDisabledByDefault` pin this | #182 |
| An explicitly invited or paired peer's `IP:port` (TCP, TLS 1.3) | **Yes, implemented** | The user accepted the invitation or pairing, and the dial is pinned to that peer's last-observed transport key (`ObservedTransportBinding`). IP literals only, never a hostname (`PeerAddress`) | #182 |
| Inbound TCP listener on a user-chosen port (0 = OS-assigned ephemeral) | **Yes, implemented** | **Off by default**; started only by `NetworkingService.enableNetworking`, stopped by `disableNetworking`/`close` | #182 |
| Link-local (administratively-scoped, RFC 2365) multicast `239.192.42.99:52735` for LAN discovery | **Yes, implemented** | **Opt-in** (`NetworkingService.enableLanDiscovery`), off by default; announcements carry no cleartext identity and are recognizable only by a peer holding the pairwise recognition key (`LanAnnouncementCodec`) | #182 |
| The user's own router via UPnP-IGD / NAT-PMP | Maybe, later | Opt-in only. It is the user's device, not a service | future, if proposed |
| A consenting participant peer acting as forwarder | Yes, later | Opt-in on both sides, with quotas, retention, and recipient allow-list | #188 |

### Explicitly prohibited at runtime (product code and library defaults)

* Central or third-party login, OAuth/OIDC providers, and account or profile services.
* Signaling, matchmaking, rendezvous, and introduction servers.
* Hosted STUN/TURN or relay servers, including "public" or "free" ones.
* Mandatory bootstrap nodes, default DHT peers, public IPFS gateways, and pinning services.
* Managed brokers or queues (MQTT, Kafka, cloud pub/sub) and cloud mailboxes or storage.
* Hosted leaderboards, analytics, telemetry, crash reporting, and update checks.
* **DNS lookups by the peer features.** Invitations carry IP literals, so no resolver is implied.
* **Network time services.** The OS clock is used as-is.
* **Certificate revocation and AIA fetching.** #182 pins keys, disables OCSP/CRL/AIA, and uses no CA
  trust store.
* Fetching URLs found in peer data. The protocol has no URL fields.
* A self-hosted central server of any kind, including a "fallback".

### Not a product runtime service (distinct and allowed)

* The OS network stack, including loopback in tests.
* Build-time downloads: Maven Central dependencies and GitHub Actions CI.
* The out-of-band channel users choose for sending an invitation. CodeFit neither operates nor
  contacts it.

## 2. Current runtime dependencies (unchanged by #180)

| Artifact | Version | Network behaviour relevant here |
|---|---|---|
| `org.openjfx:javafx-controls`, `javafx-fxml` | 21.0.5 | No network use by these modules. `javafx-web` (WebView) is **not** a dependency and must not be used to render peer content |
| `org.xerial:sqlite-jdbc` | 3.46.1.0 | Extracts its bundled native library to a temp dir. No network |
| `org.apache.poi:poi-ooxml` | 5.2.5 | Local workbook parsing only. Never given peer data |
| JDK 21 (`java.base`, `jdk.crypto.ec`) | 21 | `SunEC` supplies Ed25519 and X25519. On JDK 21 it is in module **`jdk.crypto.ec`**, which a jlink/jpackage image must include (verified: provider module = `jdk.crypto.ec` on 21.0.10) |

`NoNetworkingContractTest.noRuntimeDependencyWasAddedForThisSlice` asserts that the POM still
declares exactly these dependencies plus JUnit, and none of libp2p, Netty, Bouncy Castle, Tink,
IPFS, WebRTC, ice4j, or JmDNS. **Still true after #182**: the transport, invitation, and LAN discovery
code (`com.codefit.peer.transport`, `com.codefit.peer.invitation`, `com.codefit.peer.discovery`) adds
zero new Maven dependencies — TLS is `javax.net.ssl`, framing/certificates use `java.io`/`java.security`,
and LAN discovery uses `java.net.MulticastSocket`/`javax.crypto.Mac`, all `java.base`/`SunJCE`/`SunJSSE`.
`NoHiddenServiceDependencyTest` additionally scans the #182 packages for hosted-service patterns
(STUN/TURN, bootstrap lists, signaling/rendezvous, IP-check services, telemetry) and hardcoded external
hostnames.

## 3. Cryptography and transport: selection record

Sources were checked on 2026-09-26.

| Need | Choice | Status | Evidence |
|---|---|---|---|
| Signatures (identity, envelopes, bindings) | JDK `Signature.getInstance("Ed25519")` | **Used in #180** | Java SE 21 Standard Algorithm Names; RFC 8032 §7.1 vectors 1–3 reproduced by `Ed25519ConformanceTest` |
| Hashing (ids, fingerprints) | JDK SHA-256 | **Used in #180** | Required on every Java SE platform |
| Transport security | JDK JSSE `TLSv1.3`, mutual auth, Ed25519 self-signed certificates, key-pinning trust manager | **Used in #182** (`TlsContexts`, `PinnedTransportTrustManager`, `StructuralTransportTrustManager`) | `TlsContextsTest`/`PeerSessionTest` complete real loopback mutual TLS 1.3 handshakes (`TLS_AES_256_GCM_SHA384`) and reject a wrong pin outright |
| Self-signed certificate creation | small in-repo DER template (zero-dependency option) | **Used in #182** (`Der`, `SelfSignedCertificateFactory`) — Bouncy Castle was not needed | `SelfSignedCertificateFactoryTest` parses the generated certificate with the JDK's own `CertificateFactory`, verifies its self-signature, and enforces its validity window |
| Key encryption at rest (#181) | JDK `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding` | **Used in #181 and #182** | #182's transport key reuses `PassphraseCipher` unchanged (`TransportKeyService`) rather than adding a second at-rest scheme |
| LAN discovery recognition tags | JDK `Mac.getInstance("HmacSHA256")` | **Used in #182** (`LanAnnouncementCodec`) | `SunJCE`-provided HMAC, required on every Java SE platform; keyed by a pairwise value derived from both peers' already-known identity keys, never a value exchanged over the network |
| Sealed forwarding (#188) | HPKE (RFC 9180) via Tink `com.google.crypto.tink:tink` 1.23.0 (Apache-2.0) or Bouncy Castle 1.86 | **Decide in #188** | JDK 21 has no HPKE and no public HKDF. The KDF API is final only in JDK 25 (JEP 510), after preview in JDK 24 (JEP 478). Tink's optional KMS integrations are separate modules and must not be added |
| P2P framework | jvm-libp2p 1.3.7 | **Not selected for the MVP**; possible #188 feasibility spike | Kotlin, MIT/Apache-2.0, JDK 11+. Published on Cloudsmith and JitPack, not Maven Central. Pulls Netty 4.2, protobuf, Bouncy Castle, `noise-java`, and native QUIC and BoringSSL binaries for five targets. Several components are beta. DHT, relay, and identify defaults would need auditing and disabling |
| NAT traversal via public infrastructure (STUN/TURN, WebRTC, Tor, I2P, IPFS/DHT) | — | **Rejected** | Each needs operator-run servers, directory authorities, or bootstrap lists |

Rule: **no custom cryptographic primitives or handshakes.** The JDK and the libraries above provide
primitives and standard protocols (TLS 1.3, HPKE). CodeFit composes them only in the documented,
reviewed ways.

## 4. Audit checklist for any new peer-feature dependency

Before a dependency is added (#182 and later), the PR must record each of the following:

1. Licence, maintainer activity, latest release, and Maven Central availability.
2. Java 21 compatibility, and whether it has JPMS module names or native code (with jpackage cost
   per OS).
3. Every **default** that can cause egress: bootstrap lists, telemetry, update checks, DNS, NTP,
   OCSP/AIA, public relays. Each must be off or unused, backed by a test.
4. Thread and shutdown behaviour. Nothing may survive `Application.stop()`.
5. An update to this inventory and to `NoNetworkingContractTest`'s dependency expectations.
