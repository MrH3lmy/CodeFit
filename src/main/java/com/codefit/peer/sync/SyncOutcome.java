package com.codefit.peer.sync;

/**
 * Why one received {@link com.codefit.peer.protocol.SignedEnvelope} was accepted or rejected by
 * #184's sync layer. The codec/receiver-policy values are named identically to
 * {@link com.codefit.peer.protocol.RejectionReason} on purpose ({@link #from} maps one to the other
 * by name) so the ordered rule-set documented in {@code docs/p2p/protocol-v1.md} §10 - which
 * {@code com.codefit.peer.protocol.EnvelopeAcceptancePolicy} already encodes in memory - has exactly
 * one definition of what each outcome means; this type exists only because #184 evaluates those same
 * rules against SQLite-persisted state (that class's own state is deliberately in-memory and its
 * mutators are package-private, by the protocol layer's own design - see {@code
 * AuthorReplayState}'s javadoc) and needs two additional outcomes the protocol layer has no opinion
 * on: whether the sender is even a known, paired contact, and whether their own latest {@code
 * CONSENT_REVISION} to this receiver actually grants the body's required scope.
 */
public enum SyncOutcome {
    ACCEPTED,

    // ----- mirrors RejectionReason's codec (structural) reasons, by name -----
    MALFORMED,
    UNSUPPORTED_VERSION,
    OVERSIZED,
    UNSUPPORTED_MESSAGE_TYPE,
    UNSUPPORTED_SCHEMA_VERSION,
    NON_CANONICAL,
    INVALID_AUDIENCE,
    INVALID_TIMESTAMP,
    INCONSISTENT_BODY,
    BAD_SIGNATURE,

    // ----- mirrors RejectionReason's receiver-policy (stateful) reasons, by name -----
    UNAUTHORIZED_AUDIENCE,
    NOT_YET_VALID,
    EXPIRED,
    DUPLICATE,
    STALE_EPOCH,
    STALE_REVISION,
    FORKED,
    TOMBSTONED,

    // ----- #184-owned additions: not a protocol-layer concept -----
    /** The envelope's author is not a known, paired contact of this receiver at all. */
    UNKNOWN_AUTHOR,
    /** The connected peer's authenticated transport identity differs from the envelope's signed author. */
    SENDER_MISMATCH,
    /** The author's own latest accepted {@code CONSENT_REVISION} to this receiver does not grant the required scope. */
    SCOPE_NOT_GRANTED;

    public boolean accepted() {
        return this == ACCEPTED;
    }

    /** Maps a protocol-layer {@link com.codefit.peer.protocol.RejectionReason} to the outcome of the same name. */
    public static SyncOutcome from(com.codefit.peer.protocol.RejectionReason reason) {
        return SyncOutcome.valueOf(reason.name());
    }

    /**
     * Whether a receive loop can safely keep reading further frames from this connection after this
     * outcome, versus whether the stream's framing itself may be desynchronized and the connection
     * must be closed. Every rejection reason other than {@link #MALFORMED}/{@link #UNSUPPORTED_VERSION}
     * is detected only after the frame's fixed 8-byte header and its exact, already-bounds-checked
     * payload length were fully read, so the stream position is always well-defined for the next
     * frame; {@code docs/p2p/protocol-v1.md} §12 calls this "drop that message and keep the session."
     * {@code MALFORMED} (bad magic, truncated header) and {@code UNSUPPORTED_VERSION} mean the bytes
     * read as a header cannot be trusted to even be a header, so there is no safe resynchronization
     * point.
     */
    public boolean connectionRecoverable() {
        return this != MALFORMED && this != UNSUPPORTED_VERSION;
    }
}
