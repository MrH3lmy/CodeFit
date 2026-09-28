package com.codefit.peer.invitation;

/**
 * Why a candidate invitation blob was refused. Every reason is a safe drop: decoding an invitation
 * never executes, imports, or fetches anything, and — per #182's requirement — a structurally valid,
 * signature-verified, unexpired invitation still does not by itself create a trusted contact. It only
 * yields an {@link Invitation} value the caller may choose to show the user and, only on explicit
 * acceptance, register as a pending contact.
 */
public enum InvitationRejectionReason {
    MALFORMED,
    OVERSIZED,
    UNSUPPORTED_FORMAT_VERSION,
    BAD_SIGNATURE,
    EXPIRED,
    NOT_YET_VALID,
    INVALID_TIMESTAMPS,
    INVALID_ADDRESS,
    TRANSPORT_KEY_EQUALS_IDENTITY_KEY
}
