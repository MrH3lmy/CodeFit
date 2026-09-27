package com.codefit.peer.protocol;

/**
 * Why a received frame/envelope was rejected. Codec reasons are structural and deterministic; the
 * receiver-policy reasons ({@link EnvelopeAcceptancePolicy}) also depend on local clock and state.
 * Every reason is a safe drop: nothing a peer sends can make the receiver execute, fetch, or import it.
 */
public enum RejectionReason {
    // ----- codec (structural) -----
    /** Bad magic, truncated input, trailing bytes, invalid enum/boolean/presence code. */
    MALFORMED,
    /** Frame major version is not {@link ProtocolVersion#MAJOR}. */
    UNSUPPORTED_VERSION,
    /** Declared frame/body/string/list length exceeds its bound (checked before reading). */
    OVERSIZED,
    /** A message type this build does not implement (including reserved types). */
    UNSUPPORTED_MESSAGE_TYPE,
    /** A known message type with a body schema version this build does not implement. */
    UNSUPPORTED_SCHEMA_VERSION,
    /** Structurally readable, but not the one canonical encoding (e.g. unsorted list, non-NFC text). */
    NON_CANONICAL,
    /** Audience kind/shape invalid: empty, unsorted, duplicated, oversized, or containing the author. */
    INVALID_AUDIENCE,
    /** Timestamp outside protocol bounds, expiry not after creation, or lifetime too long. */
    INVALID_TIMESTAMP,
    /** Body fields contradict each other or the envelope (e.g. READY with a failing critical gate). */
    INCONSISTENT_BODY,
    /** Ed25519 signature does not verify for the author key over the signing bytes. */
    BAD_SIGNATURE,

    // ----- receiver policy (stateful) -----
    /** This receiver's identity is not in the envelope audience. */
    UNAUTHORIZED_AUDIENCE,
    /** {@code createdAt} is further in the future than the allowed clock skew. */
    NOT_YET_VALID,
    /** {@code expiresAt} is not after the receiver's current time. */
    EXPIRED,
    /** Same message id already accepted; idempotent no-op. */
    DUPLICATE,
    /** Author epoch lower than one already accepted from this author (a late message from an abandoned session). */
    STALE_EPOCH,
    /** Object {@code (epoch, revision)} not newer than the version already held. */
    STALE_REVISION,
    /** Two different messages share one author (epoch, sequence): two writers or a rollback. */
    FORKED,
    /** At or below a tombstone cutoff for the object: a replay of a revoked or deleted version. */
    TOMBSTONED
}
