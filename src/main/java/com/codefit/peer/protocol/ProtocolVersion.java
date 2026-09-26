package com.codefit.peer.protocol;

/**
 * Wire-level constants for the CodeFit peer protocol v1 (#180). See
 * {@code docs/p2p/protocol-v1.md} for the normative byte layout these limits belong to.
 *
 * <p>This package is a pure contract/codec layer: it opens no sockets, starts no listeners, reads no
 * files, and generates no production identity. Production networking stays disabled until #182.
 */
public final class ProtocolVersion {

    /**
     * Frame header major version. A different major is rejected as {@link
     * RejectionReason#UNSUPPORTED_VERSION}.
     */
    public static final int MAJOR = 1;
    /**
     * Envelope minor version written by this build. Minors only add message types or body schema
     * versions.
     */
    public static final int MINOR = 0;

    /** ASCII {@code "CFP"} followed by the major-version byte. */
    static final byte[] FRAME_MAGIC = {0x43, 0x46, 0x50};
    /** Magic (3) + major (1) + payload length (4). */
    public static final int FRAME_HEADER_BYTES = 8;
    /** Upper bound on one frame's payload, checked before any payload byte is read or buffered. */
    public static final int MAX_FRAME_PAYLOAD_BYTES = 64 * 1024;
    /** Upper bound on one envelope body. */
    public static final int MAX_BODY_BYTES = 60 * 1024;
    /** Recipients per envelope; there is no public/broadcast audience in v1. */
    public static final int MAX_RECIPIENTS = 32;
    /** Longest permitted envelope lifetime ({@code expiresAt - createdAt}). */
    public static final long MAX_LIFETIME_MILLIS = 400L * 24 * 60 * 60 * 1000;
    /**
     * Earliest acceptable {@code createdAt}/{@code capturedAt} (2024-01-01T00:00:00Z); older values
     * are malformed.
     */
    public static final long MIN_TIMESTAMP_MILLIS = 1_704_067_200_000L;
    /** Latest acceptable timestamp (2200-01-01T00:00:00Z), keeping arithmetic far from overflow. */
    public static final long MAX_TIMESTAMP_MILLIS = 7_258_118_400_000L;
    /** Receiver clock-skew tolerance for {@code createdAt} in the future. */
    public static final long MAX_CLOCK_SKEW_MILLIS = 5L * 60 * 1000;

    /** Domain-separation prefix for envelope signatures; never reused for any other signed structure. */
    static final byte[] ENVELOPE_SIGNATURE_CONTEXT =
            "CodeFit-P2P-Envelope-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** Domain-separation prefix for preparation-profile definition fingerprints. */
    static final byte[] PROFILE_FINGERPRINT_CONTEXT =
            "CodeFit-Preparation-Profile-Definition-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private ProtocolVersion() {
    }
}
