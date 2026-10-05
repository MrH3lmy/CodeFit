# CodeFit peer protocol v1 (wire contract)

Status: **v1.0, accepted for #180.** Normative for #181–#189. Implemented by
`com.codefit.peer.protocol` and pinned by the golden frames in
`src/test/resources/peer-protocol/v1/`. Related: [ADR-0001](adr-0001-decentralized-peer-architecture.md),
[threat model](threat-model.md), [runtime dependency inventory](runtime-dependency-inventory.md).

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

This slice defines bytes and rules only. It opens no socket, adds no listener, reads no file and
generates no production identity. Transport (#182), persistence (#181/#184) and UI (#187) come later.

## 1. Design rules

1. **One encoding per value.** Every structure has exactly one valid byte form. Decoders re-encode
   what they parsed and reject any difference (`NON_CANONICAL`). That makes signatures, message ids
   and golden fixtures deterministic.
2. **Bounded before read.** Every length (frame, body, string, list, recipients) is checked against a
   fixed bound *before* allocating or reading it.
3. **Closed schemas.** v1 has no unknown-field extension area. New fields mean a new body
   `schemaVersion`. New message kinds mean a new `messageType` code. Codes are only ever appended.
4. **Authenticated everything.** All envelope fields, including audience and timestamps, are inside
   the signing bytes.
5. **Untrusted data only.** Nothing received is executed, deserialized as a Java object, fetched
   (there are no URL fields), imported as a database or rendered as markup.
6. **Signatures prove authorship, not truth.** A valid signature means "published by this identity
   key". It never means the score was earned honestly.

## 2. Primitive encoding

| Type | Encoding |
|---|---|
| `u8`, `u16`, `u32` | unsigned big-endian, 1/2/4 bytes |
| `i64` | two's-complement big-endian, 8 bytes |
| `bool` / presence flag | one byte, exactly `0x00` or `0x01`; any other value is `MALFORMED` |
| `bytes[N]` | exactly N raw bytes |
| `string(max)` | `u16` byte length (≤ max, else `OVERSIZED`) followed by well-formed UTF-8 (else `MALFORMED`) |
| `optional<T>` | presence flag, then `T` if present |
| `list<T>(max)` | `u16` count (≤ max, else `OVERSIZED`) followed by the items |
| `instant` | `i64` Unix epoch **milliseconds, UTC**, in `[2024-01-01T00:00Z, 2200-01-01T00:00Z]`, else `INVALID_TIMESTAMP` |
| enum code | `u8` wire code; unknown codes are `MALFORMED` unless noted |

**Identifiers** (metric, profile and domain ids) match `[a-z0-9][a-z0-9._-]{0,63}` (ASCII, so they
compare the same way as bytes and as strings). **Display text** (display name, bio) MUST already be
Unicode NFC (else `NON_CANONICAL`). It MUST have no leading or trailing whitespace. It MUST NOT
contain control, format (this includes bidi overrides such as U+202E), private-use, surrogate or
line/paragraph-separator characters. Unassigned code points are allowed on purpose: the set of
assigned code points depends on the JDK's Unicode version, and every peer has to reach the same
verdict.

## 3. Frame

```
offset size field
0      3    magic = 0x43 0x46 0x50 ("CFP")
3      1    major version = 0x01
4      4    payloadLength u32 (≤ 65 536)
8      n    payload = envelope (section 4)
```

A reader MUST check the magic, then the major version (`UNSUPPORTED_VERSION`), then
`payloadLength ≤ 65 536` (`OVERSIZED`), in that order. It MUST do this from the 8-byte header alone,
before reading or buffering the payload (`EnvelopeCodec.payloadLength`). A frame whose total length
is not `8 + payloadLength` is `MALFORMED`. Over a stream transport (#182), one TLS connection
carries a sequence of frames.

## 4. Envelope

```
u16          minorVersion        (0 in v1.0; informational within major 1)
u8           messageType         (section 6)
u16          schemaVersion       (1 for every v1.0 type)
bytes[32]    authorKey           Ed25519 public key, RFC 8032 encoding
bytes[16]    objectId            logical object; stable across revisions
u32          epoch               writer-session epoch, 1..epochAt(createdAt) (section 10.1)
i64          sequence            1..2^63-1 (top bit set ⇒ MALFORMED)
u32          revision            1..2^32-1; objects order by (epoch, revision)
instant      createdAt
instant      expiresAt
audience                         (section 5)
u32 + bytes  body                length ≤ 61 440 bytes (else OVERSIZED)
bytes[64]    signature           Ed25519 over the signing bytes
```

Header rules: `expiresAt > createdAt`, `expiresAt − createdAt ≤ 400 days`, and
`epoch ≤ epochAt(createdAt)`, where `epochAt(t) = 1 + ⌊(t − 2024-01-01T00:00Z) / 1 s⌋`, capped at
2³²−1 (all `INVALID_TIMESTAMP`). Instants have millisecond precision.

**Signing bytes** = ASCII `"CodeFit-P2P-Envelope-v1"`, then `0x00`, then the major version byte
`0x01`, then every envelope byte before `signature`. The context string separates envelope
signatures from every other signed structure (a later invitation format MUST use its own context).

**Message id** = SHA-256(complete payload including signature). It is computed and never
transmitted, so it cannot disagree with the bytes it names. It is the duplicate-detection key.
Ed25519 as implemented by the JDK is deterministic and rejects non-canonical `S` values, so one
signed message has one id.

**Decode order** (so that all receivers report the same reason for the same bytes): frame checks →
`minorVersion` → `messageType` (`UNSUPPORTED_MESSAGE_TYPE`) → `schemaVersion`
(`UNSUPPORTED_SCHEMA_VERSION`) → header fields and audience → body length bound → trailing bytes →
**signature** (`BAD_SIGNATURE`) → body schema → canonical re-encoding. Body semantics are parsed
only after the signature verifies.

## 5. Audience

```
u8          kind                 1 = DIRECT, 2 = GROUP; other ⇒ INVALID_AUDIENCE
bytes[16]   groupId              GROUP only; MUST NOT be all-zero
u8          count                DIRECT 1..32, GROUP 2..32
bytes[32]×n recipient ids        IdentityId = SHA-256(recipient identity key)
```

Recipients MUST be strictly ascending as unsigned bytes: sorted, with no duplicates. The author
MUST NOT be listed. Any violation is `INVALID_AUDIENCE`. v1 has no public or broadcast audience. A
receiver that is not listed rejects the envelope as `UNAUTHORIZED_AUDIENCE`, even when valid bytes
reached it by forwarding. A group id grants nothing by itself: membership is the explicit list.

## 6. Message types

| Code | Type | v1.0 | Scope required from the recipient's consent |
|---|---|---|---|
| 1 | `IDENTITY_BINDING` | implemented | none (control) |
| 2 | `SOCIAL_PROFILE_CARD` | implemented | `SOCIAL_PROFILE` |
| 3 | `PROGRESS_SUMMARY` | implemented | `DAILY_SUMMARY` or `WEEKLY_SUMMARY` by window kind |
| 4 | `PREPARATION_SNAPSHOT` | implemented | `PREPARATION_SNAPSHOT` |
| 5 | `CONSENT_REVISION` | implemented | none (control) |
| 6 | `TOMBSTONE` | implemented | none (control) |
| 7 | `CHALLENGE_MANIFEST` | reserved → `UNSUPPORTED_MESSAGE_TYPE` | `CHALLENGE_PARTICIPATION` (#186) |
| 8 | `FORWARDED_CIPHERTEXT` | reserved → `UNSUPPORTED_MESSAGE_TYPE` | none; outer carrier only (#188) |
| 9 | `MATCH_INVITATION` | implemented | none (control) (#187) |
| 10 | `MATCH_RESPONSE` | implemented | none (control) (#187) |

Sharing scopes (`u8`): 1 `SOCIAL_PROFILE`, 2 `DAILY_SUMMARY`, 3 `WEEKLY_SUMMARY`,
4 `PREPARATION_SNAPSHOT`, 5 `CHALLENGE_PARTICIPATION`, 6 `MATCH_PARTICIPATION` (#187; distinct from
`CHALLENGE_PARTICIPATION`, which stays reserved for #186's own later, separate group-challenge
epic). No scope exists for raw content, and none may be added (see section 11).

### 6.1 `IDENTITY_BINDING` (schema 1)

```
bytes[32] transportKey   Ed25519 key in the device's self-signed TLS certificate
instant   validFrom
instant   validUntil     > validFrom, ≤ 400 days later; ≤ envelope expiresAt
```

The transport key MUST differ from the identity key. A TLS peer is authenticated as identity `I`
only if its certificate public key equals a `transportKey` that `I` bound and that is currently
valid. To revoke a transport key, tombstone the binding's `objectId`.

### 6.2 `SOCIAL_PROFILE_CARD` (schema 1)

```
string(64)            displayName      display text, ≥ 1 code point
optional<string(280)> bio              display text
string(64)            comparisonZoneId IANA-style region id, [A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*
u8                    weekStart        ISO day of week 1 (Mon)..7 (Sun)
```

`objectId` MUST equal the first 16 bytes of SHA-256(`"CodeFit-Social-Profile-Card-v1\0"` ‖ authorId).
This makes the card a single revisable object per author. Every field is self-asserted, and names
are neither unique nor verified. v1 has **no avatar, URL or contact field**. The zone id is checked
for syntax only: an id missing from the receiver's tzdata makes local-time display unavailable, but
does not make the message invalid. The social profile is a different concept from
`InterviewPreparationProfile` (a company/role competency definition) and never references one.

### 6.3 `PROGRESS_SUMMARY` (schema 1)

```
window:
  u8          kind            1 = DAY, 2 = WEEK
  i64         localStartDate  epoch day of the local start date
  string(64)  zoneId          IANA-style region id
  u8          weekStart       WEEK only; MUST equal localStartDate's ISO day of week
  instant     start
  instant     end             DAY: 22h ≤ end−start ≤ 26h; WEEK: 166h ≤ end−start ≤ 170h
instant       cutoff          start ≤ cutoff ≤ end, and cutoff ≤ envelope createdAt
list<metric>(32) metrics      1..32, strictly ascending by (metricId, metricVersion)

metric:
  string(64)  metricId
  u16         metricVersion   ≥ 1
  u8          unit            1 COUNT, 2 BASIS_POINTS (0..10 000), 3 PERCENT (0..100), 4 SECONDS
  u8          availability    1 MEASURED, 2 INSUFFICIENT_DATA, 3 UNAVAILABLE
  i64         value           ≥ 0 and within the unit range; MUST be 0 unless MEASURED
  u32         sampleSize      MUST be 0 when UNAVAILABLE
  u8          provenance      section 8
  u8          timestampBasis  section 7.3
```

The window's `start` and `end` are the authoritative half-open UTC interval `[start, end)`. See
section 7 for how they are computed and why receivers do not recompute them. A summary with
`cutoff < end` is a **partial period**.

### 6.4 `PREPARATION_SNAPSHOT` (schema 1)

```
string(64)   profileId
bytes[32]    profileFingerprint       section 9
u16          scoringVersion           ≥ 1 (PreparationSnapshots.SCORING_VERSION = 1)
u8           overallThresholdPercent  0..100 (InterviewReadinessService.DEFAULT_POLICY = 75)
instant      capturedAt               ≤ envelope createdAt
optional<u8> overallPercent           0..100; absent when nothing is measurable
u8           coveragePercent          0..100
u8           status                   1 READY, 2 NOT_READY, 3 INSUFFICIENT_DATA
list<domain>(64) domains              1..64 in profile order (the engine's order), unique ids;
                                      weights sum to exactly 100

domain:
  string(64)   domainId
  u8           weightPercent
  bool         criticalGate
  optional<u8> thresholdPercent       required when criticalGate
  optional<u8> scorePercent
  u8           coveragePercent        MUST equal round(measured × 100 / total), half up; 0 when total = 0
  u16          measuredRequirementCount ≤ total
  u16          totalRequirementCount
  u8           status                 1 PASS, 2 FAIL, 3 PARTIAL, 4 MEASURED, 5 NOT_MEASURED
```

The domain rules mirror `InterviewReadinessService` exactly:

* `NOT_MEASURED` holds exactly when there is no score, and a score exists exactly when
  `measured ≥ 1`. A domain with nothing measured, including 0 of 0, can never carry a score or
  `PASS`.
* `PASS`, `FAIL` and `PARTIAL` apply only to critical gates. A scored critical gate is never
  `MEASURED`.
* `PASS` requires `measured == total` and `score ≥ threshold`.
* `PARTIAL` requires `measured < total` and `score ≥ threshold`. A critical gate with 199 of 200
  requirements measured shows 100 % coverage and is still `PARTIAL`.
* `FAIL` MAY carry `score ≥ threshold`, because direct mock evidence below the threshold forces
  `FAIL`.

The receiver **re-derives the aggregates** and rejects any disagreement (`INCONSISTENT_BODY`). It
reproduces `InterviewReadinessService.buildResult` operation for operation in IEEE-754 binary64. It
does not use exact rationals, because the engine's own rounding decides ties such as thirds that
sum to x.5:

```
eff(d)      = d.score present ? d.weight × (d.measured / (double) d.total) : 0.0
measured    = DoubleStream.sum(eff(d) for d in domains, in list order)     // JDK compensated sum
coverage    = Math.round(measured × 100.0 / 100)
overall     = measured > 0.0
              ? Math.round(DoubleStream.sum(d.score × eff(d) for scored d, in list order) / measured)
              : absent
```

`coveragePercent` and `overallPercent` MUST equal these values. List order is part of the
arithmetic, so domains travel in profile order, and that order is part of the profile fingerprint
(section 9). `PreparationSnapshotEngineParityTest` checks this against the real engine on 5,000
randomized profiles, including a rounding-tie case. It then re-derives the overall status:

1. Any critical gate is `FAIL` ⇒ `NOT_READY`.
2. Otherwise, any critical gate is `PARTIAL` or `NOT_MEASURED`, or `overallPercent` is absent ⇒
   `INSUFFICIENT_DATA`.
3. Otherwise, `overallPercent ≥ overallThresholdPercent` ⇒ `READY`.
4. Otherwise ⇒ `NOT_READY`.

`blockingCriticalDomainIds` is derived (critical domains that are not `PASS`) and is not
transmitted. Under these rules, a high average with a failing or incompletely covered critical gate
can never be sent as `READY`, and neither can a declared score that the domains do not support. READY does not by itself imply 100 % coverage of *non-critical*
domains, which is also true of the local engine. For that reason, UIs MUST show `coveragePercent`
beside every readiness score. Titles and descriptions are local display data and are not sent.

### 6.5 `CONSENT_REVISION` (schema 1)

```
list<u8 scope>(5) scopes   strictly ascending by code; empty = revoke everything
```

The audience MUST be DIRECT with exactly one recipient (`INVALID_AUDIENCE`). `objectId` MUST equal
the first 16 bytes of SHA-256(`"CodeFit-Consent-v1\0"` ‖ authorId ‖ recipientId). The consent
record for each (author, recipient) pair is therefore one object, and its envelope
`(epoch, revision)` gives a total order. Each revision is the **complete** set of scopes, not a
delta. An older grant that arrives after a revocation is `STALE_REVISION`, so it cannot re-enable
sharing. Granting again is simply a newer revision.

### 6.6 `TOMBSTONE` (schema 1)

```
u8   targetType             implemented type other than TOMBSTONE and CONSENT_REVISION
u8   reason                 1 REVOKED, 2 DELETED
bool requestCacheDeletion
```

The envelope's `objectId` names the object. Its `(epoch, revision)` MUST be newer than every
version the author issued for that object. A tombstone is a **cutoff**, not permanent retirement:

* Every version at or below the cutoff is refused as `TOMBSTONED`, so replayed pre-revocation or
  pre-deletion copies stay dead.
* When `requestCacheDeletion` is set, the receiver deletes its cached copy.
* A strictly newer version, which the author publishes only after sharing again (for example a
  profile card after consent is granted again), starts a new incarnation of the object and is
  accepted.

The objects whose ids are derived (profile card, consent) therefore stay usable after revocation.
Deletion is **cooperative**: a peer that ignores it cannot be forced, and product text MUST NOT
promise erasure of copies already received.

### 6.7 Reserved: `CHALLENGE_MANIFEST` (code 7, #186)

This is contract intent, and #186 fixes the bytes. The manifest is signed by the challenge creator
and has a GROUP audience that lists every member. It carries the group id, a display title
(display text), and the `(metricId, metricVersion)` and window kind being ranked. It also carries a
ranking-rule version, a minimum sample size, a join deadline and an end instant. Every member ranks
the **same accepted record set** with integer-only arithmetic and ties broken by ascending
`IdentityId`, so every device computes identical results. A member leaves by publishing a tombstone
of their participation object. The manifest never grants access to anything beyond the listed
metric.

### 6.8 Reserved: `FORWARDED_CIPHERTEXT` (code 8, #188)

This is contract intent, and #188 fixes the bytes and the library. The outer envelope is signed by
the *forwarding* peer. Its audience is the next hop and it carries a hop limit. The body is an
opaque ciphertext, sealed to the final recipient's X25519 key with HPKE (RFC 9180), plus the final
recipient's `IdentityId`, a size and an expiry. Inside the ciphertext is an ordinary signed v1
frame from the original author. The forwarder learns only the outer fields, never the inner
content. Forwarding requires explicit opt-in, a per-peer quota, a retention limit and a recipient
allow-list.

### 6.9 `MATCH_INVITATION` (schema 1, #187 "1-v-1 Study Match")

```
u8 duration   1 = 15 minutes, 2 = 30 minutes, 3 = 60 minutes
```

The challenger's proposal to start a timed study match with exactly one opponent. Control (no scope
required - like `CONSENT_REVISION`, an invitation needs no pre-existing consent to be received).
`objectId` is the match id itself: a challenger-chosen random 16 bytes (`ObjectId` already is
exactly "an author-chosen random id of a logical object"), never derived from identities. The
audience MUST be DIRECT with exactly one recipient (`INVALID_AUDIENCE`). Withdrawing a still-
unanswered invitation is a `TOMBSTONE` of this same object (reason `REVOKED`) - there is no separate
"cancelled" message type.

### 6.10 `MATCH_RESPONSE` (schema 1, #187)

```
bool               accepted
optional<instant>  startedAt   present iff accepted; <= envelope createdAt
```

The opponent's one-shot accept/decline, addressed back to the challenger under the same `objectId`
(a different `(author, objectId)` replay stream from the invitation's own, since the opponent is a
different author). Control (no scope required). `startedAt` is the authoritative instant both
participants converge on - the challenger adopts it verbatim rather than computing its own, so both
sides' local records always agree exactly. `endsAt` is never transmitted: both sides derive it
identically as `startedAt + duration`, already known to both from the invitation. The audience MUST
be DIRECT with exactly one recipient.

Match-window study progress reuses the existing `PROGRESS_SUMMARY` type unchanged, with a third
window kind (§7.1): `WindowKind.MATCH`'s `start`/`end` are the match's own agreed instants, identical
on both devices by construction, so there is no independent per-device computation to disagree
about. Its required scope is `SharingScope.MATCH_PARTICIPATION` (code 6), granted automatically and
mutually the moment a match is proposed/accepted - never a separate manual toggle.

## 7. Time windows and legacy timestamps

### 7.1 Windows

* A **DAY** is local midnight to the next local midnight in the comparison zone. A **WEEK** is
  seven local days that begin at local midnight on the explicit `weekStart`. Both are computed with
  `java.time` and carried as UTC instants (`ComparisonWindow.day` / `.week`), so DST days are 23 h
  or 25 h long.
* Windows are half-open: `[start, end)`.
* The comparison zone and week start come from the author's `SOCIAL_PROFILE_CARD`. A viewer who
  compares two peers MUST use each window's own label. Two windows are "the same local period" only
  when kind, local start date, zone and week start all match (`sameLocalPeriodAs`). When they
  differ, the comparison MUST be labelled as cross-zone.
* The UTC `start` and `end` are authoritative, and receivers MUST NOT recompute them from the label.
  Two JDKs with different tzdata could disagree, and the signature covers the author's instants.
* **Partial periods.** `cutoff < end` means "so far". An in-progress period is compared only at
  equal elapsed time: `equalElapsedCutoff(elapsed) = start + min(elapsed, length)`. A comparison of
  a complete period with a partial one MUST be labelled as such or refused.
* **MATCH** (#187) is not a calendar period at all: its `start`/`end` are a 1-v-1 Study Match's own
  explicit, mutually-agreed `startedAt`/`endsAt` (§6.10), identical on both participants' devices by
  construction rather than independently computed from a local date and zone. Because of that,
  two `MATCH` observations for the same match are always treated as aligned regardless of each
  side's own elapsed time - the "equal elapsed time" rule above exists specifically to make two
  *independently anchored* partial periods comparable, which does not apply here.
* The protocol has no rolling windows. They are computed locally from stored daily summaries.

### 7.2 Clock handling

The receiver rejects `createdAt > now + 5 min` (`NOT_YET_VALID`) and `expiresAt ≤ now`
(`EXPIRED`). The protocol assumes no network time service. Each device uses its OS clock, and
skewed clocks cause visible rejections rather than silent acceptance.

### 7.3 Legacy local timestamps (`timestampBasis`)

Existing CodeFit tables store timestamps without an offset, in two different ways:

| Basis | Code | Columns (current `main`) | Assumption |
|---|---|---|---|
| `EXACT_UTC` | 1 | all new peer-feature data | exact |
| `LEGACY_SQLITE_UTC` | 2 | `review_history.reviewed_at`, `problem_attempts.submitted_at`, `assessment_attempts.attempted_at`, `problem_solving_sessions.*`, other `DEFAULT CURRENT_TIMESTAMP` columns | SQLite `CURRENT_TIMESTAMP` is UTC; exact to the second |
| `LEGACY_LOCAL_ASSUMED_ZONE` | 3 | `interview_mock_runs.completed_at`, `problem_progress.completed_at`, `flashcards.introduced_at` (written from Java `LocalDateTime`) | interpreted in the comparison zone; approximate across DST folds and past zone changes |
| `MIXED` | 4 | a metric whose evidence spans bases | boundaries approximate |

These rows cannot be made into exact UTC evidence after the fact, and the basis travels with every
metric so a UI can show "approximate". Some existing dashboard filters compare a `LocalDateTime`
bound against the UTC `reviewed_at` column. The #183 projections MUST query with UTC bounds derived
from the window, not reuse those filters unchanged.

## 8. Metric provenance and the v1 metric catalog

Provenance codes: 1 `LOCAL_RECORD`, 2 `VERIFIED_LOCAL_VALIDATION`, 3 `SELF_RATED`,
4 `LEGACY_SELF_RATING_FALLBACK`, 5 `LEARNER_REPORTED_OUTCOME`, 6 `LOCAL_TIMER`, 7 `MOCK_SELF_SCORE`.

| metricId @ version | Unit | Min samples | Allowed provenance | Local source (#183) |
|---|---|---|---|---|
| `review.attempts@1` | COUNT | 0 | LOCAL_RECORD | `review_history` rows, `boss_battle = 0` |
| `review.verified_correct_rate@1` | BASIS_POINTS | 10 | VERIFIED_LOCAL_VALIDATION **only** | objective rows with a non-blank `validation_result` |
| `review.self_rated_success_rate@1` | BASIS_POINTS | 10 | SELF_RATED, LEGACY_SELF_RATING_FALLBACK | subjective rows and legacy objective rows without `validation_result` |
| `problem.attempts@1` | COUNT | 0 | LOCAL_RECORD | `problem_attempts` rows |
| `problem.accepted@1` | COUNT | 0 | LEARNER_REPORTED_OUTCOME | attempts the learner recorded as ACCEPTED |
| `problem.solving_seconds@1` | SECONDS | 0 | LOCAL_TIMER | phase-timer seconds on attempts |
| `mock.overall_score@1` | PERCENT | 1 | MOCK_SELF_SCORE | mean `interview_mock_runs.overall_score_percent` |

`ReviewHistory.isObjectivelyCorrect()` falls back to the self rating for legacy rows that have no
validation result. Those rows are excluded from `review.verified_correct_rate` and counted only
under `LEGACY_SELF_RATING_FALLBACK`. Availability rules for a known metric:

* `MEASURED` requires `sampleSize ≥ min`.
* `INSUFFICIENT_DATA` requires `sampleSize < min` and `value = 0`.
* `UNAVAILABLE` means "not known for this window". Examples are history from before snapshots
  existed, or a source that is not recorded. It is never back-filled with zero.

A metric with an unknown `(id, version)` is structurally valid (for forward compatibility) but
**not comparable**, and must be shown as such. Changing how a metric is computed requires a new
version.

## 9. Preparation profile fingerprint

The fingerprint is SHA-256 of `"CodeFit-Preparation-Profile-Definition-v1\0"` followed by a
canonical encoding of the following fields:

* The profile id.
* For each domain, **in profile order** (which the aggregation depends on): id, weight, critical
  flag and optional threshold.
* For each requirement in the domain, sorted by id (domain scores are order-independent integer
  averages): id, `AVAILABLE`/`PLANNED`, and the optional material reference (type and key).

Titles and descriptions are excluded. There are two compatibility levels:

* **Scores comparable** (`scoresComparableWith`): `profileId`, `profileFingerprint` and
  `scoringVersion` are equal. Raw overall, domain scores and coverage may be compared as numbers, and
  the UI labels them as such.
* **Readiness comparable** (`readinessComparableWith`): additionally, `overallThresholdPercent` is
  equal. Only then are READY/NOT_READY verdicts the same grade. For example, 90 % is READY at
  threshold 75 and NOT_READY at threshold 95. Profiles are bundled CodeFit content, so
the fingerprint reveals only which definition a peer used, not learner data.

## 10. Receiver acceptance, replay and revisions

After a successful decode, `EnvelopeAcceptancePolicy` applies the following checks in order. The
first failure wins.

1. Receiver not in audience ⇒ `UNAUTHORIZED_AUDIENCE`.
2. `createdAt > now + 5 min` ⇒ `NOT_YET_VALID`.
3. `expiresAt ≤ now` ⇒ `EXPIRED`.
4. Message id already accepted ⇒ `DUPLICATE`. This is an idempotent no-op and is not reported as
   an error.
5. `epoch <` the highest epoch accepted from this author ⇒ `STALE_EPOCH`.
6. The `(epoch, sequence)` slot is already held by a *different* message id ⇒ `FORKED`. Either the
   author has two active writers, or an epoch was reused. The UI warns the user.
7. `(epoch, revision)` is not newer than the held version of the object (lexicographic order) ⇒
   `TOMBSTONED` if that held version is a tombstone, otherwise `STALE_REVISION`.
8. Otherwise accept, and record the id, the slot, and the object's `(epoch, revision, isTombstone)`.

Sequence **gaps are normal**, because each recipient receives only what was shared with it.
Whether the author's latest `CONSENT_REVISION` to this receiver grants the body's required scope is
checked by the sync layer (#184), which owns persisted consent. Data outside the granted scopes is
dropped. Replay state is in memory in #180 and is persisted in SQLite by #184. A slot entry may be
pruned after its message expires, because expired messages are rejected before the replay checks.
Replay protection is never cleared to make recovery work.

### 10.1 Writer epochs, restore, and recovery limits

A counter kept in a backup cannot guarantee a fresh epoch. Restoring the same backup twice would
reuse "backup epoch + 1", and the two restores would fork each other's slots. Epochs are therefore
derived from the **clock at the start of a writer session** (`WriterEpoch`):

* `next(previousEpoch, now) = epochAt(now)`, and the writer MUST refuse to start when
  `previousEpoch ≥ epochAt(now)`. `previousEpoch` is the highest epoch the device knows: its own
  stored value, or the value in the backup (0 if unknown).
* A new session MUST start after every restore or import, and after any local database recovery
  that may have lost `(epoch, nextSequence)` or object revisions. It MAY start on every launch.
* Receivers reject `epoch > epochAt(createdAt)`. A clock that ran ahead therefore cannot mint an
  epoch that would lock out later sessions.
* Objects order by `(epoch, revision)`. A new session supersedes every revision from older epochs,
  even when a restore rolled the local revision counter back. For example, a peer holding revision 5
  from epoch *e₁* accepts revision 2 from epoch *e₂ > e₁*. Late messages from an abandoned epoch are
  `STALE_EPOCH`, so they never overwrite newer state.
* After a restore, sharing MUST stay paused until the user reviews the restored consent state. The
  backup may predate a revocation, and the first publication in the new session is then a
  deliberate re-grant, not an accidental one.

Guarantee: any two writer sessions started at different seconds on a clock that never moves
backwards get distinct, increasing epochs, however many times one backup is restored. Recovery
limits, all visible and none silent:

| Situation | What peers see | Recovery |
|---|---|---|
| Clock behind an earlier session (`previousEpoch ≥ epochAt(now)`) | nothing; the writer refuses to start | fix the clock, or wait until it passes the earlier epoch |
| Backup older than a session peers have seen, restored on a clock set *behind* that session | `STALE_EPOCH` for the new session's messages | fix the clock and start a new session |
| Two installs with one identity running concurrently (or two sessions in the same second) | `FORKED` on colliding slots | stop one install and start a new session. **Single active writer per identity is an explicit MVP limitation.** |
| Identity private key lost, or no usable backup | nothing more from that identity | create a new identity and re-pair with contacts. There is no global revocation or recovery service |

## 11. Peer-visible boundary

Only the fields listed in sections 6.1–6.6 ever leave the device. `PeerVisibleContractTest`
enforces the list mechanically: every record component reachable from `MessageBody` must be in its
allow-list. The following never leave the device:

* Problem statements, titles or URLs, and imported workbook content.
* Flashcard fronts or backs, answers and submitted code.
* Notes, reflections, editorials and resource links.
* Deck names (outside the one-way profile fingerprint).
* Tokens and private keys.
* Database rows or files, and the complete database.

This matches `docs/problem-solving-source-attribution.md`.

## 12. Compatibility and versioning

| Change | Mechanism | Old receiver behaviour |
|---|---|---|
| Frame layout or signing change | new **major** (2) | `UNSUPPORTED_VERSION`, connection-level incompatibility |
| New message type | new type code, bump **minor** | `UNSUPPORTED_MESSAGE_TYPE`; drop that message and keep the session |
| New or changed body fields | new **schemaVersion** for that type | `UNSUPPORTED_SCHEMA_VERSION`; drop that message |
| New metric or metric formula | new `metricId` or `metricVersion` | accepted but shown as not comparable |
| Readiness formula change | bump `SCORING_VERSION` | snapshots not comparable across versions |
| Profile definition change (including domain order) | fingerprint changes | snapshots not comparable |
| Different overall readiness threshold | `overallThresholdPercent` differs | scores comparable, readiness verdicts not |

Authors SHOULD send the lowest schema version that expresses the data, and MAY send two schema
versions of the same object during a migration window. Received data is never silently upgraded.
Negotiating supported versions at session start is part of #182 (the TLS session carries an initial
`hello` exchange that lists supported major versions, types and schemas). That exchange is
transport-level and does not change these envelope bytes.

## 13. Conformance fixtures

`src/test/resources/peer-protocol/v1/*.frame.hex` holds complete frames (lowercase hex, wrapped at
64 characters) generated from `ProtocolFixtures`. All keys are published RFC 8032 §7.1 test vectors,
and all data is synthetic.

| Fixture | Covers |
|---|---|
| `identity-binding` | control message, two-recipient DIRECT audience |
| `social-profile-card` | derived object id, optional bio, zone and week start |
| `progress-summary-day-partial` | partial DAY window over a DST-free day, verified vs. legacy bases |
| `progress-summary-week-group` | complete WEEK, GROUP audience, UNAVAILABLE and INSUFFICIENT_DATA metrics |
| `preparation-snapshot` | READY with a PASS critical gate and a partially covered non-critical domain |
| `consent-revision` / `consent-revocation` | derived consent object, revision ordering, empty-scope revocation |
| `tombstone` | revocation cutoff with a cache-deletion request |

Negative conformance cases are generated by mutating these frames and re-signing them
(`EnvelopeRejectionTest`). They cover every codec rejection reason: bad magic, other majors, an
oversized header or body, truncated, trailing or mismatched lengths, unknown or reserved types, an
unknown schema, tampered bytes, a foreign signature, timestamps out of range or out of order, an
epoch later than `createdAt`, snapshot aggregates that contradict their domains, a PASS gate with
nothing measured,
negative or zero counters, all audience shape errors, non-NFC text, control and bidi characters,
invalid UTF-8, non-binary booleans, a wrong derived object id, unsorted metrics, a consent audience
with two recipients, and a summary newer than its envelope. An independent implementation is
conformant when it produces these bytes for the same inputs and returns the same rejection reasons.
