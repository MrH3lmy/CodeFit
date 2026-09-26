package com.codefit.peer.protocol;

/**
 * A typed, peer-visible envelope body. Implementations are closed records whose every component is an
 * approved aggregate or self-asserted field; raw learner content and key material have no place in
 * this hierarchy (enforced by {@code PeerVisibleContractTest}).
 */
public sealed interface MessageBody
        permits IdentityBinding, SocialProfileCard, ProgressSummary, PreparationSnapshot, ConsentRevision, Tombstone {

    MessageType type();

    /** Body schema version for {@link #type()}; v1.0 implements schema 1 of every implemented type. */
    default int schemaVersion() {
        return 1;
    }

    /** Canonical body bytes (the envelope's length-prefixed body field). */
    byte[] encodeBody();

    /**
     * The sharing scope a recipient must have been granted, or {@code null} for protocol control
     * data.
     */
    default SharingScope requiredScope() {
        return type().fixedScope();
    }

    /** Cross-checks between body and envelope header, e.g. audience shape or timestamps. */
    default void validateAgainst(EnvelopeHeader header) {
    }
}
