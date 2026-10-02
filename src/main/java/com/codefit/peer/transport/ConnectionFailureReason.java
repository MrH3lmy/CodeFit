package com.codefit.peer.transport;

/** Why a connection attempt did not (or no longer) produce an authenticated session. */
public enum ConnectionFailureReason {
    NONE,
    CONNECT_TIMEOUT,
    CONNECTION_REFUSED,
    NETWORK_UNREACHABLE,
    TLS_HANDSHAKE_FAILED,
    WRONG_PIN,
    UNSUPPORTED_VERSION,
    MALFORMED_FRAME,
    OVERSIZED_FRAME,
    HANDSHAKE_TIMEOUT,
    UNKNOWN_IDENTITY,
    NOT_PAIRED,
    IDENTITY_MISMATCH,
    BINDING_KEY_MISMATCH,
    BINDING_NOT_YET_VALID,
    BINDING_EXPIRED,
    ENVELOPE_REJECTED,
    /** A binding for a different or older key than the one pinned, without a strictly-newer identity-signed proof. */
    STALE_BINDING,
    /** The peer would not run (or could not complete) the transport-key rollover proof for this caller. */
    ROLLOVER_REFUSED,
    /** Authenticated, but the refreshed binding could not be stored locally. */
    BINDING_NOT_PERSISTED,
    CANCELLED,
    LISTENER_NOT_RUNNING,
    CONNECTION_LIMIT_REACHED,
    NETWORKING_DISABLED,
    NO_KNOWN_ADDRESS
}
