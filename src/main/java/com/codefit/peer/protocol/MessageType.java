package com.codefit.peer.protocol;

/**
 * Envelope message types. {@code implementedInV1_0} types have body codecs in this build; the reserved
 * types are specified in {@code docs/p2p/protocol-v1.md} so their codes are fixed now, but a v1.0
 * receiver rejects them as {@link RejectionReason#UNSUPPORTED_MESSAGE_TYPE} until their issue lands.
 */
public enum MessageType implements WireCode {
    /** Identity key authorizes one transport (TLS) key for a validity window. */
    IDENTITY_BINDING(1, true, null),
    /** Self-asserted social profile fields a contact is allowed to see. Not an interview profile. */
    SOCIAL_PROFILE_CARD(2, true, SharingScope.SOCIAL_PROFILE),
    /** Aggregate learning metrics for one explicit DAY or WEEK window. */
    PROGRESS_SUMMARY(3, true, null),
    /**
     * A dated snapshot of one {@code InterviewReadinessResult}, preserving coverage and critical
     * gates.
     */
    PREPARATION_SNAPSHOT(4, true, SharingScope.PREPARATION_SNAPSHOT),
    /** The complete set of scopes the author currently shares with exactly one recipient. */
    CONSENT_REVISION(5, true, null),
    /** Retires an object: no later or earlier revision of it is accepted again. */
    TOMBSTONE(6, true, null),
    /** Reserved for #186: private challenge/group manifest. */
    CHALLENGE_MANIFEST(7, false, SharingScope.CHALLENGE_PARTICIPATION),
    /** Reserved for #188: opaque ciphertext carried by a consenting forwarding peer. */
    FORWARDED_CIPHERTEXT(8, false, null),
    /** Challenger proposes a 1-v-1 Study Match of one predefined duration. Control (no scope). */
    MATCH_INVITATION(9, true, null),
    /** Opponent's one-shot accept/decline of a {@code MATCH_INVITATION}. Control (no scope). */
    MATCH_RESPONSE(10, true, null);

    private final int code;
    private final boolean implementedInV1_0;
    private final SharingScope fixedScope;

    MessageType(int code, boolean implementedInV1_0, SharingScope fixedScope) {
        this.code = code;
        this.implementedInV1_0 = implementedInV1_0;
        this.fixedScope = fixedScope;
    }

    @Override
    public int code() {
        return code;
    }

    public boolean implementedInV1_0() {
        return implementedInV1_0;
    }

    /**
     * The sharing scope a message of this type always requires, or {@code null} when the scope depends
     * on the body (a progress summary's window kind) or the type is protocol control data.
     */
    SharingScope fixedScope() {
        return fixedScope;
    }

    static MessageType fromWire(int code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                if (!type.implementedInV1_0) {
                    throw new ProtocolException(RejectionReason.UNSUPPORTED_MESSAGE_TYPE, type + " is reserved and not implemented in v1.0.");
                }
                return type;
            }
        }
        throw new ProtocolException(RejectionReason.UNSUPPORTED_MESSAGE_TYPE, "Unknown message type " + code);
    }
}
