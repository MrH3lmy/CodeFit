package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;

/**
 * The verdict of one handshake attempt (dial or accept): either successfully authenticated as a specific
 * paired contact, or a specific {@link ConnectionFailureReason} with a human-readable detail — never a
 * bare boolean, matching #182's "expose useful connection and failure states" requirement.
 */
public record ConnectionOutcome(boolean authenticated, IdentityId remoteIdentityId, ConnectionFailureReason failureReason,
                                 String detail) {
    static ConnectionOutcome ok(IdentityId remote) {
        return new ConnectionOutcome(true, remote, null, null);
    }

    static ConnectionOutcome fail(ConnectionFailureReason reason, String detail) {
        return new ConnectionOutcome(false, null, reason, detail);
    }
}
