package com.codefit.peer.transport;

/**
 * The lifecycle of one connection attempt or established session (#182 acceptance criterion: "Expose
 * connecting, connected, unreachable/waiting, incompatible, and rejected states with useful reasons").
 */
public enum ConnectionState {
    /** Waiting to dial, e.g. queued behind the bounded concurrent-dial limit. */
    QUEUED,
    /** TCP connect and TLS handshake in progress. */
    CONNECTING,
    /** TLS established; exchanging the version hello and {@code IDENTITY_BINDING} proof. */
    AUTHENTICATING,
    /** Fully authenticated: the peer proved it is the expected, paired identity. */
    CONNECTED,
    /** Closed normally (local or remote). */
    CLOSED,
    /** Could not reach the peer at all (connect timeout/refused/network unreachable). No hosted fallback exists. */
    UNREACHABLE,
    /** Reached the peer, but its protocol major version has no overlap with ours. */
    INCOMPATIBLE_VERSION,
    /** Reached and authenticated a real endpoint, but it was rejected (wrong pin, unknown/blocked identity, bad binding). */
    REJECTED,
    /** Explicitly cancelled by the caller before it reached a terminal state. */
    CANCELLED,
    /** Retrying after a transient failure, waiting out backoff/jitter. */
    RETRY_WAITING
}
