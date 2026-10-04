package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;

/**
 * Told, synchronously, the moment a connection becomes {@link PeerNetworkService}'s current tracked
 * connection for its remote identity - inbound or outbound, never for a connection that failed any
 * check. The critical invariant: by the time this fires, {@code
 * PeerNetworkService.activeConnection(remoteIdentityId)} already returns exactly {@code connection} -
 * callers must never need to rediscover it. This is deliberately a different, narrower guarantee than
 * {@link VerifiedBindingListener} offers: that listener's own inbound firing happens <em>before</em>
 * the connection is tracked (see {@code PeerListener#handle}), which is wrong for anything that needs
 * to act on "this is now the real, current connection for this identity" - exactly the reason this
 * interface exists instead of reusing it.
 *
 * <p>Fires on whatever background thread established the connection (the dial pool or the handshake
 * pool) - never the caller's thread. An implementation must return quickly and must not perform
 * blocking I/O directly here: it is invoked from inside {@link PeerNetworkService}'s own connection-
 * tracking map update, so a slow implementation would hold up tracking for other connections to the
 * same remote identity.
 */
@FunctionalInterface
public interface ConnectionEstablishedListener {
    void onConnectionEstablished(IdentityId remoteIdentityId, PeerConnection connection);
}
