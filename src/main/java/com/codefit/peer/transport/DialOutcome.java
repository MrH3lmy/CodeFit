package com.codefit.peer.transport;

/**
 * The outcome of one dial attempt. {@code connection} is present exactly when
 * {@code result.authenticated()} is true, and the caller then owns it (must eventually
 * {@link PeerConnection#close()} it) — a successful attempt hands back the same live, open socket the
 * handshake ran on rather than closing it, since the whole point of dialing is to end up with a usable
 * connection, not merely to test reachability.
 */
public record DialOutcome(ConnectionOutcome result, PeerConnection connection) {
}
