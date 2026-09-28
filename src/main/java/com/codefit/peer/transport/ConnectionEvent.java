package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;

import java.time.Instant;

/**
 * One state transition for a connection attempt, delivered to whatever local listener a future UI
 * (#187) registers. {@code remoteIdentityId} is {@code null} until authentication learns who the peer
 * is (e.g. an inbound connection is still just an anonymous socket while {@code CONNECTING}).
 */
public record ConnectionEvent(IdentityId remoteIdentityId, ConnectionState state, ConnectionFailureReason reason,
                               String detail, Instant at) {

    static ConnectionEvent of(IdentityId remoteIdentityId, ConnectionState state) {
        return new ConnectionEvent(remoteIdentityId, state, null, null, Instant.now());
    }

    static ConnectionEvent failed(IdentityId remoteIdentityId, ConnectionState state, ConnectionFailureReason reason, String detail) {
        return new ConnectionEvent(remoteIdentityId, state, reason, detail, Instant.now());
    }
}
